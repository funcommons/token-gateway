package fun.commons.tokengateway.worker;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 任务执行 Worker 主类 (独立进程, 独立扩缩, 《05》§3).
 *
 * <p>只扫 worker 包; 复用 face-task 的鉴权/快照加密经 WorkerConfiguration 显式装配,
 * 不扫描 face-task 的 controller (Worker 无对外数据面端点, 仅 dry-run 管理端点).
 */
@SpringBootApplication(scanBasePackages = "fun.commons.tokengateway.worker")
public class TaskWorkerApplication {

    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(TaskWorkerApplication.class, args);
    }
}
