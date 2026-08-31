package fun.commons.tokengateway.spi;

import fun.commons.tokengateway.spi.model.TaskCreateRequest;
import fun.commons.tokengateway.spi.model.TaskCreateVO;
import fun.commons.tokengateway.spi.model.TaskPollVO;
import fun.commons.tokengateway.spi.model.TokenContext;
import reactor.core.publisher.Mono;

/**
 * 任务委托面 (TASK_CREATE / TASK_POLL, 设计方案 §4.2) — 可选.
 *
 * <p>仅当后端自持任务状态 (后端本身是任务平台) 时启用: 网关不做本地状态机,
 * create/poll 直委后端. 默认形态是网关本地状态机 (face-task, THMP 移植):
 * 走 route 面解析上游后由网关驱动 create/poll/notify/资源代理.
 *
 * <p>幂等: 同 taskNo 重复 create 返回首次结果; poll 只读.
 */
public interface TaskClient extends CapabilityFacade {

    /** 任务创建委托 (taskNo 由网关生成并预扣后下发); 失败回业务 code, 网关全额退款并置 FAILED. */
    Mono<TaskCreateVO> create(TaskCreateRequest request);

    /** 任务轮询委托 (只读). */
    Mono<TaskPollVO> poll(String taskNo, TokenContext ctx);

    @Override
    default Capability capability() {
        return Capability.TASK_CREATE;
    }
}
