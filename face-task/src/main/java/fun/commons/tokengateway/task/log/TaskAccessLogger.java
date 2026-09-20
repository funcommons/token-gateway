package fun.commons.tokengateway.task.log;

import fun.commons.tokengateway.contract.AccessLogRequest;
import fun.commons.tokengateway.contract.TokenValidateRequest;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.rpc.HttpAccessLogApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务面访问日志上报 (issue #29): 受理 + 终态各一条 (约 +2 条/任务), fire-and-forget.
 *
 * <p>排障四系统 (work→网关→lotask→worker) 此前断在网关——face-task 零 AccessLog 引用,
 * 任务链无调用日志. 本组件与 LLM 面 AccessLogReporter 同纪律 (失败仅 WARN, 零异常外抛,
 * 主链零阻塞), 但不复用它: 任务面无渠道健康语义, 直接走 {@link HttpAccessLogApi}.
 *
 * <p>契约对齐 (#25/#26 定稿, 不二次改契约): {@link AccessLogRequest} 无 taskNo 字段,
 * taskNo 以 {@code ?task_no=} 查询参数附在 requestPath 上 (受理=受理端点路径,
 * 终态=webhook 端点路径 + 映射后终态); 细分 token 维 (reasoning/audio/cacheCreation)、
 * creditConsumed/billingMode 任务面无来源, 恒 null (不造数, 旧消费方忽略).
 *
 * <p>身份回查: 受理时 controller 层无 TokenValidateVO (编排器内部消化, 编排器一行不改),
 * 受理上报链在响应返回后后台回查一次 validate 换取 tenantId/userId/apiKeyId
 * (不在关键路径上加延迟); 回查失败记录照落, 身份留空. 终态时 TaskMeta 无调用方身份,
 * 身份恒空, 串联键=taskNo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskAccessLogger {

    /** 同步生图 data[0].url (sig 代理 URL) 中回收 taskNo: /v1/resources/{taskNo}/0?... */
    private static final Pattern RESOURCE_URL_TASK_NO = Pattern.compile("/v1/resources/([^/?]+)/");

    /** lotask 终态 webhook 接收端点 (与 LotaskWebhookController 同路径). */
    private static final String WEBHOOK_PATH = "/internal/lotask/webhook";

    private final HttpTokenApi tokenApi;
    private final HttpAccessLogApi accessLogApi;

    /**
     * 受理上报 (fire-and-forget: 内部 subscribe, create 响应链零等待).
     *
     * <p>controller 层在 create 成功路径 {@code doOnNext} 中调用 (PENDING 响应返回前);
     * 失败类受理 (401/402/4xx/5xx) 不上报 (与 LLM 面成功路径语义一致).
     *
     * @param requestPath 受理端点路径 (如 /v1/onetoken/videos)
     * @param model       请求模型 (body.model, 两协议均顶层)
     * @param taskNo      网关任务号 (null 时只落路径, 不附参数)
     * @param apiKey      调用方凭证 (后台回查 validate 换身份, 与受理同参)
     * @param clientIp    调用方客户端 IP (validate 透传, 与受理同参)
     * @param traceId     链路 ID (X-Trace-Id; null 时生成 UUID, 同 LLM 面纪律)
     * @param latencyMs   create 耗时 (controller 层计时; 同步生图=完整同步封装耗时)
     */
    public void reportCreated(String requestPath, String model, String taskNo,
                              String apiKey, String clientIp, String traceId, long latencyMs) {
        createdChain(requestPath, model, taskNo, apiKey, clientIp, traceId, latencyMs).subscribe();
    }

    /**
     * 终态上报 (fire-and-forget): TerminalEventHandler 成功收口终态 (settle/refund +
     * 终态落账 + pending 闭环) 后调用; statusCode=200 为 webhook 接收成功语义
     * (终态处理失败不落此记录——记录缺失即处理异常, 对账兜底可查).
     *
     * @param taskNo         网关任务号 (串联键)
     * @param submitTaskType submit task_type (TaskMeta.modality; submit-task-type=model 时即模型编码)
     * @param terminalStatus 映射后网关终态 (SUCCEEDED/FAILED/EXPIRED)
     */
    public void reportTerminal(String taskNo, String submitTaskType, String terminalStatus) {
        terminalChain(taskNo, submitTaskType, terminalStatus).subscribe();
    }

    /**
     * 受理上报链 (validate 身份回查 → record; 永不错误, 供测试编排).
     * <p>回查失败 (RPC fail 包络/key 已失效) 不丢记录: 身份留空, taskNo/model/traceId 仍串联.
     */
    Mono<Void> createdChain(String requestPath, String model, String taskNo,
                            String apiKey, String clientIp, String traceId, long latencyMs) {
        String path = taskNo == null ? requestPath : requestPath + "?task_no=" + taskNo;
        return Mono.defer(() -> {
            if (apiKey == null || apiKey.isBlank()) {
                return record(path, model, traceId, null, latencyMs);
            }
            return tokenApi.validate(TokenValidateRequest.builder()
                            .apiKey(apiKey).clientIp(clientIp).model(model).build())
                    .flatMap(resp -> {
                        TokenValidateVO token = resp != null && resp.isSuccess()
                                && resp.getData() != null && resp.getData().isValid()
                                ? resp.getData() : null;
                        return record(path, model, traceId, token, latencyMs);
                    });
        }).onErrorResume(e -> {
            log.warn("[TaskAccessLog] 受理上报失败 (不影响主链): path={}, err={}", path, e.getMessage());
            return Mono.empty();
        });
    }

    /** 终态上报链 (record; 永不错误, 供测试编排). */
    Mono<Void> terminalChain(String taskNo, String submitTaskType, String terminalStatus) {
        return Mono.defer(() -> accessLogApi.record(AccessLogRequest.builder()
                        .traceId(UUID.randomUUID().toString())
                        .modelCode(submitTaskType)
                        .requestMethod("POST")
                        .requestPath(WEBHOOK_PATH + "?task_no=" + taskNo + "&status=" + terminalStatus)
                        .statusCode(200)
                        .build()))
                .onErrorResume(e -> {
                    log.warn("[TaskAccessLog] 终态上报失败 (不影响主链): taskNo={}, err={}",
                            taskNo, e.getMessage());
                    return Mono.empty();
                });
    }

    /** 受理记录构造 (身份来自回查 validate; token/计费维任务面无来源, null 不造数). */
    private Mono<Void> record(String path, String model, String traceId,
                              TokenValidateVO token, long latencyMs) {
        return accessLogApi.record(AccessLogRequest.builder()
                .traceId(traceId != null ? traceId : UUID.randomUUID().toString())
                .tenantId(parseLong(token != null ? token.getTenantId() : null))
                .userId(parseLong(token != null ? token.getUserId() : null))
                .apiKeyId(parseLong(token != null ? token.getTokenId() : null))
                .modelCode(model)
                .requestMethod("POST")
                .requestPath(path)
                .statusCode(200)
                .latencyMs((int) Math.min(Math.max(latencyMs, 0), Integer.MAX_VALUE))
                .build());
    }

    /** 请求体 model 提取 (OneToken/OpenAI 两协议均顶层 model; null 安全). */
    public static String modelOf(Map<String, Object> body) {
        Object model = body == null ? null : body.get("model");
        return model == null ? null : String.valueOf(model);
    }

    /**
     * 创建响应视图 taskNo 回收 (四形状): OneToken {task_no} / OpenAI job {id} /
     * PROCESSING 降级 {task_no} / 同步生图 {data:[{url:/v1/resources/{taskNo}/...}]}.
     */
    public static String taskNoOf(Map<String, Object> view) {
        if (view == null) {
            return null;
        }
        Object taskNo = view.get("task_no");
        if (taskNo == null) {
            taskNo = view.get("id");
        }
        if (taskNo != null) {
            return String.valueOf(taskNo);
        }
        if (view.get("data") instanceof List<?> data && !data.isEmpty()
                && data.get(0) instanceof Map<?, ?> item
                && item.get("url") instanceof String url) {
            Matcher m = RESOURCE_URL_TASK_NO.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
