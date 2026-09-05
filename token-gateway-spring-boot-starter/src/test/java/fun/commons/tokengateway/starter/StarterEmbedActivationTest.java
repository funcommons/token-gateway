package fun.commons.tokengateway.starter;

import fun.commons.tokengateway.controller.ChatCompletionController;
import fun.commons.tokengateway.relay.RelayOrchestrator;
import fun.commons.tokengateway.trace.TraceWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实宿主嵌入冒烟: 测试宿主应用不 import 任何网关类,
 * 仅凭 classpath 上 starter 的 AutoConfiguration.imports 装配
 * (验证用户侧「引用即嵌入」路径, 行为与 app 独立部署同构).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "token-gateway.face=llm")
class StarterEmbedActivationTest {

    @SpringBootApplication
    static class EmbedHostApp {
        public static void main(String[] args) {
            SpringApplication.run(EmbedHostApp.class, args);
        }
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void starterAssemblesGatewayWithoutAnyHostImport() {
        assertThat(context.getBeanNamesForType(ChatCompletionController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(RelayOrchestrator.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(TraceWebFilter.class)).hasSize(1);
    }
}
