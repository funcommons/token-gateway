package fun.commons.tokengateway.app;

import fun.commons.tokengateway.rpc.CapabilityEndpoints;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动冒烟: 全 bean 装配走查 (随机端口, 不连 Redis —— Lettuce 懒连接).
 *
 * <p>回归守护: gateway.thmp.enabled=false (默认) 时 ThmpCutover 无 bean 会导致
 * RelayOrchestrator 装配失败, 此类缺陷只有上下文级测试能捕获.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenGatewayApplicationTest {

    @Autowired
    private CapabilityEndpoints endpoints;

    @Test
    void contextLoads() {
        // 上下文装配成功即通过
    }

    /**
     * 回归 2026-09-21-04 BL11 P1-5: yml 默认须与 gateway-spi 默认同源 —
     * route 面 (LLM chat 分发) 与 task 面 (work 域分发) 两键分离, 不得再单键共用.
     */
    @Test
    void distributePathsSplitPerFace() {
        assertThat(endpoints.route().getPath())
                .isEqualTo("/api/v1/internal/channels/distribute");
        assertThat(endpoints.taskRoute().getPath())
                .isEqualTo("/api/v1/internal/work-channels/distribute");
    }
}
