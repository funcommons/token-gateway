package fun.commons.tokengateway.face;

import fun.commons.tokengateway.config.ConditionalOnFace;
import fun.commons.tokengateway.spi.config.Face;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LLM 面装配 (face=llm | all 时生效).
 *
 * <p>face=llm 部署分组: 纯同步面, 无数据库无本地盘, 按连接数弹性扩缩 (设计方案 §9).
 *
 * <p>EnableScheduling: billing 待重放扫描 BillingReconcileJob (issue #23;
 * face=llm 独立部署时任务面的 TaskFaceConfiguration 不在 context, 须自带开关).
 */
@Configuration
@EnableScheduling
@ConditionalOnFace(Face.LLM)
@ComponentScan(basePackages = {
        "fun.commons.tokengateway.controller",
        "fun.commons.tokengateway.relay",
        "fun.commons.tokengateway.upstream",
        "fun.commons.tokengateway.format"})
public class FaceLlmAssembly {
}
