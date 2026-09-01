package fun.commons.tokengateway.worker.script;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groovy 沙箱 (《05》§9.5: 黑名单 + 只允许经 http/json binding 触网).
 *
 * <p>AST 层收口: 禁用 System/Runtime/ProcessBuilder/Thread/IO/网络/反射/类加载;
 * 不允许 import 上述包; 禁用 static 字段访问的间接逃逸 (receiver 黑名单).
 * 编译缓存按 (路径, mtime) 失效 (脚本热更即换版本).
 *
 * <p>注意: 沙箱是纵深防御的一层, 不是安全边界——脚本仍跑在 Worker 进程内,
 * 出网由 ScriptHttpClient 白名单收口, 执行超时由 ScriptExecutor 硬上限收口.
 */
@Component
public class GroovySandbox {

    /** 直接类引用黑名单 (AST receiver 级, 类名字符串). */
    private static final List<String> RECEIVERS_BLACKLIST = List.of(
            "java.lang.System", "java.lang.Runtime", "java.lang.ProcessBuilder",
            "java.lang.Thread", "java.io.File", "java.nio.file.Files",
            "java.nio.file.Path", "java.nio.file.Paths",
            "java.net.Socket", "java.net.ServerSocket", "java.net.URL",
            "java.net.URLConnection", "java.lang.ClassLoader",
            "groovy.lang.GroovyClassLoader",
            "java.lang.reflect.Method", "java.lang.reflect.Field");

    /** import 黑名单 (包级). */
    private static final List<String> IMPORTS_BLACKLIST = List.of(
            "java.io", "java.net", "java.nio", "java.lang.reflect", "java.lang.invoke",
            "groovy.lang.GroovyShell", "groovy.lang.GroovyClassLoader", "groovy.util.Eval",
            "jdk", "sun", "com.sun");

    private final CompilerConfiguration compilerConfiguration;
    private final Map<String, Class<?>> compileCache = new ConcurrentHashMap<>();
    private final GroovyClassLoader classLoader;

    public GroovySandbox() {
        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setReceiversBlackList(RECEIVERS_BLACKLIST);
        secure.setImportsBlacklist(IMPORTS_BLACKLIST);
        secure.setPackageAllowed(false);
        secure.setClosuresAllowed(true);
        // receivers 黑名单只管方法调用, 管不到构造器 (new File(...) 逃逸) — 补构造器检查
        secure.addExpressionCheckers((org.codehaus.groovy.control.customizers
                .SecureASTCustomizer.ExpressionChecker) expr -> {
            if (expr instanceof org.codehaus.groovy.ast.expr.ConstructorCallExpression cce) {
                return !RECEIVERS_BLACKLIST.contains(cce.getType().getName());
            }
            return true;
        });
        compilerConfiguration = new CompilerConfiguration();
        compilerConfiguration.addCompilationCustomizers(secure);
        classLoader = new GroovyClassLoader(
                GroovySandbox.class.getClassLoader(), compilerConfiguration);
    }

    /**
     * 编译并缓存脚本 (缓存键 = 路径 + mtime, 热更换版本自动失效).
     *
     * @param cacheKey 缓存键 (脚本路径)
     * @param source   脚本文本
     * @throws ScriptRejectedException 触碰黑名单 (编译期拒绝, 不到运行期)
     */
    @SuppressWarnings("unchecked")
    public Class<? extends groovy.lang.Script> compile(String cacheKey, String source) {
        return (Class<? extends groovy.lang.Script>) compileCache.computeIfAbsent(
                cacheKey, k -> {
                    try {
                        return classLoader.parseClass(source, k);
                    } catch (org.codehaus.groovy.control.MultipleCompilationErrorsException e) {
                        throw new ScriptRejectedException(
                                "脚本编译/安检拒绝: " + e.getMessage(), e);
                    }
                });
    }

    /** 脚本触碰黑名单 (编译期拒绝). */
    public static class ScriptRejectedException extends RuntimeException {
        public ScriptRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
