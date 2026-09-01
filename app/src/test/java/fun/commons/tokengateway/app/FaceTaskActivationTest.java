package fun.commons.tokengateway.app;

import fun.commons.tokengateway.controller.ChatCompletionController;
import fun.commons.tokengateway.relay.RelayOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * face=task 部署分组: LLM 面组件不装配 (独立部署隔离, 设计方案 §9).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "token-gateway.face=task")
class FaceTaskActivationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void llmFaceBeansAbsent() {
        assertThat(context.getBeanNamesForType(ChatCompletionController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(RelayOrchestrator.class)).isEmpty();
    }
}
