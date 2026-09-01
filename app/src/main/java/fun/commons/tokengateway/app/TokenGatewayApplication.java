package fun.commons.tokengateway.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * token-gateway 通用模型能力网关 (当前实现 LLM 同步面; 任务四模态面规划中 M2.5,
 * 见 docs/用户文档/02_任务面接入手册.md).
 *
 * <p>装配结构 (设计方案 §9 分模块方案):
 * <ul>
 *   <li>gateway-core: 共享基建 (信封/横切/RPC/THMP/配置), 零 JDBC</li>
 *   <li>face-llm: LLM 同步面, {@code token-gateway.face=llm|all} 时装配</li>
 *   <li>face-task: 任务面 (M2.5), {@code token-gateway.face=task|all} 时装配,
 *       独占数据库与资源缓存盘依赖</li>
 * </ul>
 *
 * <p>部署分组: 同 jar 异配置 —— face=llm 无盘弹性扩 / face=task 挂盘挂库 /
 * face=all 单组合跑 (默认).
 *
 * <p>scanBasePackages 只扫 core 包 + face 装配类; 各面组件由 FaceXxxAssembly
 * 按 face 条件扫描, 避免 face=task 实例加载 LLM 端点 (反之亦然).
 */
@SpringBootApplication(scanBasePackages = {
        "fun.commons.tokengateway.config",
        "fun.commons.tokengateway.exception",
        "fun.commons.tokengateway.trace",
        "fun.commons.tokengateway.ratelimit",
        "fun.commons.tokengateway.idempotency",
        "fun.commons.tokengateway.rpc",
        "fun.commons.tokengateway.thmp",
        "fun.commons.tokengateway.moderation",
        "fun.commons.tokengateway.util",
        "fun.commons.tokengateway.face"})
public class TokenGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(TokenGatewayApplication.class, args);
    }
}
