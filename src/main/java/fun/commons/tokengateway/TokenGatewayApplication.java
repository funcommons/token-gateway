package fun.commons.tokengateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * token-gateway 通用模型能力网关 (当前实现 LLM 同步面; 任务四模态面规划中 M2.5,
 * 见 docs/用户文档/02_任务面接入手册.md).
 *
 * <p>代码基底为 MMagiX gateway-webflux 平移 (设计方案 M1): relay/upstream/rpc/thmp 全套.
 * 不依赖 backend/* 任何模块, 也不依赖 framework4j (避免 servlet 类加载冲突).
 * 通过 HTTP RPC (WebClient) 调后端能力面端点拿 token/channel/计费/审核数据.
 *
 * <p>能力面 SPI 化见 docs/开发文档/01_设计方案.md (M0 接口先行).
 */
@SpringBootApplication
public class TokenGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(TokenGatewayApplication.class, args);
    }
}
