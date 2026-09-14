package fun.commons.tokengateway.task.controller;

import fun.commons.tokengateway.task.relay.TaskRelayOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAI 协议任务端点测试 (issue #19): 参数/鉴权透传、background 路由、content 307.
 * <p>视图映射与生命周期在 TaskRelayOrchestratorTest 覆盖, 此处只验控制器薄层.
 */
@DisplayName("OpenAI 协议任务端点")
class OpenAiTaskControllerTest {

    private TaskRelayOrchestrator orchestrator;
    private OpenAiTaskController controller;
    private OpenAiImagesTaskController imagesController;

    @BeforeEach
    void setUp() {
        orchestrator = mock(TaskRelayOrchestrator.class);
        controller = new OpenAiTaskController(orchestrator);
        imagesController = new OpenAiImagesTaskController(orchestrator);
    }

    @Test
    @DisplayName("POST /v1/videos: Bearer 提取 + traceId/幂等键透传 + job 透传")
    void createVideoDelegates() {
        Map<String, Object> job = Map.of("id", "T1", "status", "queued");
        when(orchestrator.createVideoJob(eq("sk-x"), any(), eq("tr-1"), eq("idem-1")))
                .thenReturn(Mono.just(job));

        StepVerifier.create(controller.createVideo("Bearer sk-x", null, "tr-1", "idem-1",
                        Map.of("model", "sora-2", "prompt", "p")))
                .assertNext(resp -> assertThat(resp.get("id")).isEqualTo("T1"))
                .verifyComplete();
        verify(orchestrator).createVideoJob(eq("sk-x"), any(), eq("tr-1"), eq("idem-1"));
    }

    @Test
    @DisplayName("videos content: 307 重定向至签名代理 URL")
    void videoContentRedirects() {
        when(orchestrator.videoContentUrl("T7", "sk-x"))
                .thenReturn(Mono.just("/v1/resources/T7/0?exp=1&sig=z"));

        StepVerifier.create(controller.videoContent("T7", "Bearer sk-x", null))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(307);
                    assertThat(resp.getHeaders().getLocation().toString())
                            .isEqualTo("/v1/resources/T7/0?exp=1&sig=z");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("images: background:true → 异步 job; 缺省 → 同步封装 (createImageGenerations)")
    void imagesRoutesByBackgroundFlag() {
        when(orchestrator.createImageJob(eq("sk-x"), any(), anyString(), anyString()))
                .thenReturn(Mono.just(Map.of("id", "T2", "status", "queued")));
        when(orchestrator.createImageGenerations(eq("sk-x"), any(), anyString(), anyString()))
                .thenReturn(Mono.just(Map.of("created", 1L)));

        StepVerifier.create(imagesController.createImages("Bearer sk-x", null, "tr", "idem",
                        Map.of("model", "gpt-image-2", "prompt", "p", "background", true)))
                .assertNext(resp -> assertThat(resp.get("status")).isEqualTo("queued"))
                .verifyComplete();
        verify(orchestrator).createImageJob(eq("sk-x"), any(), eq("tr"), eq("idem"));

        StepVerifier.create(imagesController.createImages("Bearer sk-x", null, "tr", "idem",
                        Map.of("model", "gpt-image-2", "prompt", "p")))
                .assertNext(resp -> assertThat(resp).containsKey("created"))
                .verifyComplete();
        verify(orchestrator).createImageGenerations(eq("sk-x"), any(), eq("tr"), eq("idem"));
    }

    @Test
    @DisplayName("images GET: 幂等键无关的 job 轮询透传")
    void imageJobPollDelegates() {
        when(orchestrator.imageJob("T5", "sk-x"))
                .thenReturn(Mono.just(Map.of("id", "T5", "status", "in_progress")));

        StepVerifier.create(imagesController.getImageJob("T5", null, "sk-x"))
                .assertNext(resp -> assertThat(resp.get("status")).isEqualTo("in_progress"))
                .verifyComplete();
    }
}
