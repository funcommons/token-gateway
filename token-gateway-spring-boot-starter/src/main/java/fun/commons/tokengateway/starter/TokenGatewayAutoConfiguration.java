package fun.commons.tokengateway.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * token-gateway 嵌入模式自动装配 (starter).
 *
 * <p>宿主应用引用本 starter 即装配网关, 装配口径与独立部署 app 模块完全同构:
 * scanBasePackages 同款十包 (core 八包 + face 装配包), face 包内
 * FaceLlmAssembly / FaceTaskAssembly 按 {@code token-gateway.face = llm | task | all}
 * 条件装配各面组件 (非法值启动 fail-fast).
 *
 * <p>条件:
 * <ul>
 *   <li>仅 reactive web 宿主装配 —— 网关为 WebFlux 栈 (WebFilter / RouterFunction),
 *       MVC 宿主下静默不装配 (见用户文档 02 快速开始「嵌入模式」)</li>
 *   <li>{@code token-gateway.enabled=false} 一键关闭 (缺省开启)</li>
 * </ul>
 *
 * <p>Worker 嵌入: {@link WorkerAssembly} 按需装配任务执行 Worker
 * ({@code token-gateway.worker.enabled=true} 且 face=task|all, 缺省关闭) ——
 * 嵌入任务面的完整闭环无需独立部署 Worker 进程.
 *
 * <p>限制: 网关内部 bean 经 component-scan 注册, 嵌入方不支持以 @Bean 覆盖替换.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "token-gateway", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Import(WorkerAssembly.class)
@ComponentScan(basePackages = {
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
public class TokenGatewayAutoConfiguration {
}
