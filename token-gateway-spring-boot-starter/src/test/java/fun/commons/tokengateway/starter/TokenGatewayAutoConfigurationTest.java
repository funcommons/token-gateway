package fun.commons.tokengateway.starter;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.controller.ChatCompletionController;
import fun.commons.tokengateway.relay.RelayOrchestrator;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.task.relay.TaskRelayOrchestrator;
import fun.commons.tokengateway.trace.TraceWebFilter;
import fun.commons.tokengateway.worker.WorkerLoop;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 嵌入模式装配矩阵: face 分组 / enabled 开关 / 非法值 fail-fast
 * (行为与独立部署 app 的三个 ActivationTest 同构).
 */
class TokenGatewayAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            // 复刻宿主自动装配链中网关组件依赖的提供方: Redis 连接工厂/template (限流/幂等),
            // WebClient.Builder (rpc 层); 真实宿主由 Boot 全链提供, runner 裸上下文需显式补
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
                    org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration.class,
                    org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration.class,
                    TokenGatewayAutoConfiguration.class));

    @Test
    void defaultFaceAllAssemblesBothFaces() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GatewayProperties.class);
            assertThat(context).hasSingleBean(TraceWebFilter.class);
            assertThat(context).hasSingleBean(RpcInternalAuth.class);
            assertThat(context).hasSingleBean(ChatCompletionController.class);
            assertThat(context).hasSingleBean(RelayOrchestrator.class);
            assertThat(context).hasSingleBean(TaskRelayOrchestrator.class);
        });
    }

    @Test
    void faceLlmExcludesTaskBeans() {
        runner.withPropertyValues("token-gateway.face=llm").run(context -> {
            assertThat(context).hasSingleBean(ChatCompletionController.class);
            assertThat(context).doesNotHaveBean(TaskRelayOrchestrator.class);
        });
    }

    @Test
    void faceTaskExcludesLlmBeans() {
        runner.withPropertyValues("token-gateway.face=task").run(context -> {
            assertThat(context).hasSingleBean(TaskRelayOrchestrator.class);
            assertThat(context).doesNotHaveBean(ChatCompletionController.class);
        });
    }

    @Test
    void enabledFalseAssemblesNothing() {
        runner.withPropertyValues("token-gateway.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(GatewayProperties.class);
            assertThat(context).doesNotHaveBean(ChatCompletionController.class);
            assertThat(context).doesNotHaveBean(TaskRelayOrchestrator.class);
        });
    }

    @Test
    void illegalFaceFailsFast() {
        // fail-fast 消息契约 (ConditionalOnFace.OnFaceCondition);
        // condition 在 component-scan 中评估, ISE 被包装进 BeanDefinitionStoreException
        runner.withPropertyValues("token-gateway.face=bogus").run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("token-gateway.face 非法值");
        });
    }

    @Test
    void workerDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ChatCompletionController.class);
            assertThat(context).doesNotHaveBean(WorkerLoop.class);
        });
    }

    @Test
    void workerEnabledAssemblesLoopInTaskFace() {
        runner.withPropertyValues("token-gateway.worker.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(WorkerLoop.class);
            assertThat(context).hasSingleBean(TaskRelayOrchestrator.class);
        });
    }

    @Test
    void workerEnabledIgnoredInLlmFace() {
        // face=llm 无任务面, Worker 即使显式开启也不装配
        runner.withPropertyValues("token-gateway.face=llm",
                "token-gateway.worker.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ChatCompletionController.class);
            assertThat(context).doesNotHaveBean(WorkerLoop.class);
        });
    }
}
