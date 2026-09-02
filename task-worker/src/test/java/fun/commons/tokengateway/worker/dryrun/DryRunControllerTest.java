package fun.commons.tokengateway.worker.dryrun;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.lotask.RouteSnapshotCipher;
import fun.commons.tokengateway.worker.config.WorkerProperties;
import fun.commons.tokengateway.worker.script.GroovySandbox;
import fun.commons.tokengateway.worker.script.ScriptHttpClient;
import fun.commons.tokengateway.worker.script.ScriptExecutor;
import fun.commons.tokengateway.worker.script.ScriptLoader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DryRunController 测试 (真实 GroovySandbox + ScriptExecutor, ScriptLoader mock):
 * 参数校验 / 无脚本 400 / 钩子输出与耗时 / 脚本异常落 error / ctx 默认值注入.
 */
class DryRunControllerTest {

    private static final String SCRIPT = """
            // task_type: video — dry-run 样例
            def create(ctx) {
                def p = ctx.payload
                return [upstreamTaskId: "u-" + (p.model ?: "x"), echo: ctx.progress == null]
            }
            def poll(ctx) {
                return [state: "SUCCEEDED", raw: [via: ctx.payload]]
            }
            """;

    @SuppressWarnings("unchecked")
    private DryRunController controller(ScriptLoader loader) {
        GroovySandbox sandbox = new GroovySandbox();
        WorkerProperties workerProps = new WorkerProperties();
        workerProps.setHookTimeout(Duration.ofSeconds(5));
        workerProps.setEgressAllowlist(List.of("http://localhost:9999"));
        ScriptHttpClient http = new ScriptHttpClient(WebClient.builder(), workerProps);
        return new DryRunController(loader, sandbox, http, workerProps);
    }

    private static ScriptLoader loaderReturning(String taskType) {
        ScriptLoader loader = Mockito.mock(ScriptLoader.class);
        when(loader.forType(taskType)).thenReturn(Optional.of(new ScriptLoader.ScriptAsset(
                taskType, "/scripts/video/sample-v1.groovy", 42L, SCRIPT)));
        return loader;
    }

    @Test
    void missingParamsReturns400() {
        DryRunController c = controller(Mockito.mock(ScriptLoader.class));
        assertThat(c.dryRun(new DryRunController.DryRunRequest(null, "create", null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.dryRun(new DryRunController.DryRunRequest("video", null, null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.dryRun(new DryRunController.DryRunRequest(null, null, null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownTaskTypeReturns400() {
        DryRunController c = controller(Mockito.mock(ScriptLoader.class));
        ResponseEntity<Map<String, Object>> out = c.dryRun(
                new DryRunController.DryRunRequest("audio", "create", null));
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(out.getBody()).containsEntry("error", "无脚本: audio");
    }

    @Test
    void createHookRunsAndInjectsDefaults() {
        DryRunController c = controller(loaderReturning("video"));
        ResponseEntity<Map<String, Object>> out = c.dryRun(
                new DryRunController.DryRunRequest("video", "create",
                        Map.of("payload", Map.of("model", "vid-mock-1"))));
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = out.getBody();
        assertThat(body).containsEntry("ok", true)
                .containsKey("elapsedMs").containsKey("script");
        assertThat(String.valueOf(body.get("script"))).endsWith(".groovy");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) body.get("output");
        assertThat(output.get("upstreamTaskId")).isEqualTo("u-vid-mock-1");
        // 未传 progress → 控制器注入默认空 Map (非 null)
        assertThat(output.get("echo")).isEqualTo(false);
    }

    @Test
    void scriptExceptionFallsIntoErrorBody() {
        String bad = "def create(ctx) { throw new RuntimeException('hook blew up') }";
        ScriptLoader loader = Mockito.mock(ScriptLoader.class);
        when(loader.forType("video")).thenReturn(Optional.of(
                new ScriptLoader.ScriptAsset("video", "/x/v.groovy", 1L, bad)));
        ResponseEntity<Map<String, Object>> out = controller(loader).dryRun(
                new DryRunController.DryRunRequest("video", "create", null));
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(out.getBody()).containsEntry("ok", false);
        assertThat(String.valueOf(out.getBody().get("error"))).contains("hook blew up");
    }

    @Test
    void ctxNullPayloadDefaultsToEmptyMap() {
        DryRunController c = controller(loaderReturning("video"));
        ResponseEntity<Map<String, Object>> out = c.dryRun(
                new DryRunController.DryRunRequest("video", "poll", null));
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(out.getBody()).containsEntry("ok", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) out.getBody().get("output");
        assertThat(output.get("state")).isEqualTo("SUCCEEDED");
        assertThat((Map<String, Object>) output.get("raw")).containsEntry("via", Map.of());
    }
}
