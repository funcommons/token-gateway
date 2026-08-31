package fun.commons.tokengateway.spi.model;

import java.util.Map;

/**
 * 任务创建委托请求 (TASK_CREATE 面入参, M0 冻结契约, 后端接入手册 §4.8).
 *
 * @param taskNo   任务号 (网关生成并预扣后下发)
 * @param model    任务模型名
 * @param params   模态参数 (duration/resolution/…, 按模型而定)
 * @param notifyUrl 终态回调地址 (由网关承接 HMAC 签名重发, 后端无需感知)
 * @param traceId  链路 ID
 */
public record TaskCreateRequest(
        String taskNo,
        String model,
        Map<String, Object> params,
        String notifyUrl,
        String traceId) {
}
