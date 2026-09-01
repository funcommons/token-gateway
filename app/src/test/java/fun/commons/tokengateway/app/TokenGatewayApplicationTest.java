package fun.commons.tokengateway.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 启动冒烟: 全 bean 装配走查 (随机端口, 不连 Redis —— Lettuce 懒连接).
 *
 * <p>回归守护: gateway.thmp.enabled=false (默认) 时 ThmpCutover 无 bean 会导致
 * RelayOrchestrator 装配失败, 此类缺陷只有上下文级测试能捕获.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenGatewayApplicationTest {

    @Test
    void contextLoads() {
        // 上下文装配成功即通过
    }
}
