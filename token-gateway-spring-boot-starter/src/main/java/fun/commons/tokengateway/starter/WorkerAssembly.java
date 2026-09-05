package fun.commons.tokengateway.starter;

import fun.commons.tokengateway.config.ConditionalOnFace;
import fun.commons.tokengateway.spi.config.Face;
import fun.commons.tokengateway.worker.config.WorkerConfiguration;
import fun.commons.tokengateway.worker.config.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务执行 Worker 嵌入装配 (token-gateway.worker.enabled=true 时生效).
 *
 * <p>嵌入任务面 (face=task | all) 的完整闭环: 网关接单 + 宿主进程内 Worker 拉单执行,
 * 引用 starter 即完整 token-gateway, 无需独立部署 Worker 进程. 独立部署场景
 * (task-worker 独立进程独立扩缩) 不受影响 —— 不配 worker.enabled 即不装配.
 *
 * <p>条件叠加:
 * <ul>
 *   <li>{@code token-gateway.worker.enabled=true} 显式开启 (缺省关闭) ——
 *       拉单循环依赖 lotask4j 平台与脚本目录, 未配置任务的嵌入方不该空转</li>
 *   <li>{@code token-gateway.face = task | all} —— face=llm 嵌入无任务面, Worker 无意义</li>
 * </ul>
 *
 * <p>与独立部署 WorkerConfiguration 的装配分工 (排除之, 避免同进程 bean 冲突):
 * <ul>
 *   <li>EnableScheduling / WorkerProperties 绑定: 本类提供</li>
 *   <li>TokenGatewayProperties 绑定: TaskFaceConfiguration 提供 (face=task|all 必装配)</li>
 *   <li>lotask 鉴权三件套 (AuthSigner/TokenStore/SnapshotCipher): task 包扫描提供
 *       (独立 worker 进程不扫 task 包, 故 WorkerConfiguration 显式 Import —— 嵌入场景冗余)</li>
 * </ul>
 */
@Configuration
@ConditionalOnFace(Face.TASK)
@ConditionalOnProperty(prefix = "token-gateway", name = "worker.enabled",
        havingValue = "true", matchIfMissing = false)
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
@ComponentScan(basePackages = "fun.commons.tokengateway.worker",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = WorkerConfiguration.class))
public class WorkerAssembly {
}
