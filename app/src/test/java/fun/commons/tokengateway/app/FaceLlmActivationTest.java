package fun.commons.tokengateway.app;

import fun.commons.tokengateway.controller.ChatCompletionController;
import fun.commons.tokengateway.relay.RelayOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * face=llm 部署分组: LLM 面装配, 任务面组件不出现.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "token-gateway.face=llm")
class FaceLlmActivationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void llmFaceBeansPresent() {
        assertThat(context.getBeanNamesForType(ChatCompletionController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(RelayOrchestrator.class)).hasSize(1);
    }
}
