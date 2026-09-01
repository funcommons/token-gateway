package fun.commons.tokengateway.face;

import fun.commons.tokengateway.config.ConditionalOnFace;
import fun.commons.tokengateway.spi.config.Face;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 任务面装配 (face=task | all 时生效).
 *
 * <p>face=task 部署分组: 挂资源缓存盘, 依赖 lotask4j 平台 (任务状态托管,
 * 05_任务面lotask4j托管方案, 2026-09-01 决议); 本面无数据库.
 * 任务域代码 (fun.commons.tokengateway.task.*) 随 M2.5 落地 —
 * 四模态 caller 端点 + 计费 saga + notify + 资源代理 + LotaskTaskClient.
 */
@Configuration
@ConditionalOnFace(Face.TASK)
@ComponentScan(basePackages = "fun.commons.tokengateway.task")
public class FaceTaskAssembly {
}
