package fun.commons.tokengateway.worker.script;

import com.alibaba.fastjson2.JSON;
import groovy.lang.Binding;
import groovy.lang.Script;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 脚本三钩子执行器 (《05》§9.2/§9.5).
 *
 * <p>Binding 注入: ctx (Map, 含 payload/routeSnapshot/upstreamTaskId/progress),
 * http (ScriptHttpClient, 出网白名单收口), json (fastjson2 包装), log.
 * 执行: 有界守护线程池 + 单钩子超时硬上限 (超时/异常 → ScriptHookException,
 * Worker 归一映射 FAILED + error_code=SCRIPT_ERROR).
 */
@Slf4j
public class ScriptExecutor {

    private final GroovySandbox sandbox;
    private final Duration hookTimeout;
    private final ExecutorService executor;

    public ScriptExecutor(GroovySandbox sandbox, Duration hookTimeout) {
        this.sandbox = sandbox;
        this.hookTimeout = hookTimeout;
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "script-hook-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newCachedThreadPool(factory);
    }

    /** 脚本函数式 json binding. */
    public static final class JsonTool {
        public Object parse(String raw) {
            return JSON.parse(raw);
        }

        public String stringify(Object obj) {
            return JSON.toJSONString(obj);
        }
    }

    /**
     * 执行一个钩子.
     *
     * @param cacheKey 脚本缓存键 (路径+mtime)
     * @param source   脚本文本
     * @param hook     create | poll | resultMapping
     * @param ctx      钩子上下文 (脚本可读写)
     * @param http     出网 binding
     * @return 钩子返回 (Map 约定, 见《05》§9.2)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(String cacheKey, String source, String hook,
                                      Map<String, Object> ctx, ScriptHttpClient http) {
        Callable<Object> call = () -> {
            Class<? extends Script> clazz = sandbox.compile(cacheKey, source);
            Script script = clazz.getDeclaredConstructor().newInstance();
            Binding binding = new Binding();
            binding.setVariable("ctx", ctx);
            binding.setVariable("http", http);
            binding.setVariable("json", new JsonTool());
            binding.setVariable("log", org.slf4j.LoggerFactory.getLogger("script." + hook));
            script.setBinding(binding);
            script.run(); // 顶层语句 (函数定义注册)
            Object result = script.invokeMethod(hook, new Object[]{ctx});
            return result;
        };
        try {
            Object result = executor.submit(call).get(hookTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result == null) {
                return Map.of();
            }
            if (!(result instanceof Map)) {
                throw new ScriptHookException(hook, "钩子必须返回 Map, 实际: "
                        + result.getClass().getSimpleName());
            }
            return (Map<String, Object>) result;
        } catch (TimeoutException e) {
            throw new ScriptHookException(hook, "钩子执行超时 (" + hookTimeout + ")");
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException
                    && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof GroovySandbox.ScriptRejectedException sre) {
                throw new ScriptHookException(hook, "脚本安检拒绝: " + sre.getMessage());
            }
            throw new ScriptHookException(hook, "钩子异常: " + cause.getMessage());
        }
    }

    /** 钩子失败 (Worker 归一映射 FAILED + error_code=SCRIPT_ERROR + 审计). */
    public static class ScriptHookException extends RuntimeException {
        private final String hook;

        public ScriptHookException(String hook, String message) {
            super(message);
            this.hook = hook;
        }

        public String hook() {
            return hook;
        }
    }
}
