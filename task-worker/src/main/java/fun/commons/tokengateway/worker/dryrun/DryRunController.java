package fun.commons.tokengateway.worker.dryrun;

import fun.commons.tokengateway.worker.config.WorkerProperties;
import fun.commons.tokengateway.worker.script.GroovySandbox;
import fun.commons.tokengateway.worker.script.ScriptExecutor;
import fun.commons.tokengateway.worker.script.ScriptHttpClient;
import fun.commons.tokengateway.worker.script.ScriptLoader;
import fun.commons.tokengateway.worker.script.ScriptLoader.ScriptAsset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脚本 dry-run (《05》§9.4 联调层): 指定 task_type + 钩子 + 入参, 返回钩子输出与耗时,
 * 不落任务. 仅限内网/管理面暴露 (Worker 不对外开数据面).
 */
@RestController
public class DryRunController {

    private final ScriptLoader scriptLoader;
    private final ScriptExecutor scriptExecutor;
    private final ScriptHttpClient http;

    public DryRunController(ScriptLoader scriptLoader, GroovySandbox sandbox,
                            ScriptHttpClient http, WorkerProperties props) {
        this.scriptLoader = scriptLoader;
        this.scriptExecutor = new ScriptExecutor(sandbox, props.getHookTimeout());
        this.http = http;
    }

    public record DryRunRequest(String taskType, String hook, Map<String, Object> ctx) {
    }

    @PostMapping("/admin/script-test/dry-run")
    public ResponseEntity<Map<String, Object>> dryRun(@RequestBody DryRunRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (request.taskType() == null || request.hook() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "taskType/hook 必填"));
        }
        return scriptLoader.forType(request.taskType())
                .map(asset -> runHook(asset, request, out))
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(Map.of("error", "无脚本: " + request.taskType())));
    }

    private ResponseEntity<Map<String, Object>> runHook(ScriptAsset asset, DryRunRequest request,
                                                        Map<String, Object> out) {
        Map<String, Object> ctx = request.ctx() != null
                ? new LinkedHashMap<>(request.ctx()) : new LinkedHashMap<>();
        ctx.putIfAbsent("payload", Map.of());
        ctx.putIfAbsent("progress", new LinkedHashMap<>());
        long start = System.nanoTime();
        try {
            Map<String, Object> result = scriptExecutor.invoke(
                    asset.cacheKey(), asset.source(), request.hook(), ctx, http);
            out.put("ok", true);
            out.put("output", result);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        out.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
        out.put("script", asset.path());
        return ResponseEntity.ok(out);
    }
}
