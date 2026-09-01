package fun.commons.tokengateway.face;

import fun.commons.tokengateway.config.ConditionalOnFace;
import fun.commons.tokengateway.spi.config.Face;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 任务面装配 (face=task | all 时生效).
 *
 * <p>face=task 部署分组: 挂任务表数据库 + 资源缓存盘, 按带宽/磁盘扩缩 (设计方案 §9).
 * 任务域代码 (fun.commons.tokengateway.task.*) 随 M2.5 THMP 移植落地 —
 * 四模态 create/poll/notify/资源代理 + 轮询状态机 + MaintenanceScheduler 调度兜底.
 */
@Configuration
@ConditionalOnFace(Face.TASK)
@ComponentScan(basePackages = "fun.commons.tokengateway.task")
public class FaceTaskAssembly {
}
