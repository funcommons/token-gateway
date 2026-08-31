package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.contract.DistributeRequest;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.PreConsumeRequest;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.contract.TokenValidateRequest;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.controller.RelayException;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.moderation.ModerationGate;
import fun.commons.tokengateway.moderation.ModerationOutcome;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.thmp.ThmpCutover;
import fun.commons.tokengateway.thmp.ThmpShadow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 中继编排 (从 backend/gateway 抽公共逻辑, gateway-webflux 版本).
 *
 * <p>Reactor 链:
 * <ol>
 *   <li>token 校验 (TokenApi.validate)</li>
 *   <li>渠道路由 (ChannelApi.distribute)</li>
 *   <li>Moderation 输入扫描 (ModerationGate.scanInput) — BLOCK 抛 400, MASK 透传 sanitized</li>
 *   <li>预扣 (BillingApi.preConsume) — 所有渠道都走 (V087), saga 内部按 ownerType 双扣/单扣;
 *   billing 三值开关 (direct/passthrough/off, 设计方案 §5.3) 属 M1 适配器化落地</li>
 * </ol>
 *
 * <p>返回 {@link PreparedRequest} 给 Controller, Controller 调用上游前用
 *   {@link PreparedRequest#moderationSanitized()} 替换 body 末尾 user message,
 *   调用上游后用 {@link #settle} / {@link #refund} 完结 Saga.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelayOrchestrator {

    private final HttpTokenApi tokenApi;
    private final HttpChannelApi channelApi;
    private final HttpBillingApi billingApi;
    private final ModerationGate moderationGate;

    /**
     * THMP 影子双跑埋点 (22 号 S2-W1; gateway.thmp.enabled=false 时为 Noop).
     * fire-and-forget: compare 内部自订阅, 不入 Reactor 主链.
     */
    private final ThmpShadow thmpShadow;

    /**
     * THMP 灰度切流入口 (22 号 S2-W3 前置; 名单空时 Noop).
     * 命中 → THMP 候选转路由; 未命中/失败 → 空 → 回旧 distribute (一键回旧).
     */
    private final ThmpCutover thmpCutover;

    /**
     * 校验 token + 路由渠道 + 审核 + 预扣, 返回 prepared 上下文.
     *
     * <p>失败场景 (任一) 直接抛 RelayException:
     * <ul>
     *   <li>apiKey 缺失 → 401</li>
     *   <li>token RPC 失败 / token 无效 → 401</li>
     *   <li>channel RPC 失败 / 渠道为空 → 502</li>
     *   <li>moderation BLOCK → 400</li>
     *   <li>preConsume 失败 → 502; 信封 10617 (余额不足) → 402 + 10617</li>
     * </ul>
     */
    public Mono<PreparedRequest> prepare(String apiKey, String model,
                                         int estPromptTokens, int estCompletionTokens,
                                         String userContent, String requestId) {
        if (apiKey == null) {
            return Mono.error(new RelayException(401,
                    fun.commons.tokengateway.framework.ApiCode.UNAUTHORIZED.getCode(),
                    "缺少 bearer token"));
        }
        return tokenApi.validate(TokenValidateRequest.builder()
                        .apiKey(apiKey).model(model).build())
                .flatMap(tokenResp -> {
                    if (tokenResp == null || !tokenResp.isSuccess() || tokenResp.getData() == null
                            || !tokenResp.getData().isValid()) {
                        return Mono.error(new RelayException(401, "invalid token"));
                    }
                    TokenValidateVO token = tokenResp.getData();
                    return obtainRoute(token, model, requestId)
                            .flatMap(channel -> moderateInput(token, userContent)
                                    .flatMap(moderation -> {
                                        String sanitized = maskedContentOf(moderation);
                                        List<String> codes = moderation.ruleCodes() == null
                                                ? List.of() : moderation.ruleCodes();
                                        return preConsumeIfNeeded(channel, token, model,
                                                estPromptTokens, estCompletionTokens, requestId)
                                                .map(preConsumeId -> new PreparedRequest(
                                                        token, channel, preConsumeId, requestId,
                                                        sanitized, codes))
                                                .switchIfEmpty(Mono.just(new PreparedRequest(
                                                        token, channel, null, requestId,
                                                        sanitized, codes)));
                                    }));
                });
    }

    /**
     * 路由获取: W3 灰度切流 (THMP 候选执行) 优先, 未命中/失败 → 回旧 distribute
     * (22 号风险表: 一键回旧 = 配置清空名单). 切流命中跳过影子比对 (THMP 即真源);
     * 旧链路命中维持 S2-W1 双跑埋点.
     */
    private Mono<DistributeVO> obtainRoute(TokenValidateVO token, String model, String requestId) {
        Mono<DistributeVO> cutover = thmpCutover.route(model, token.getTenantId(), requestId)
                .doOnNext(vo -> log.info("[Relay] 切流命中 THMP 路由: model={} channel={} base={}",
                        model, vo.getChannelId(), vo.getBaseUrl()));
        return cutover.switchIfEmpty(Mono.defer(() -> channelApi.distribute(
                        DistributeRequest.builder()
                                .tenantId(token.getTenantId())
                                .userId(token.getUserId())
                                .apiKeyId(token.getTokenId())
                                .groupId(token.getGroupId())
                                .model(model).build())
                .flatMap(distResp -> {
                    if (distResp == null || !distResp.isSuccess() || distResp.getData() == null) {
                        String reason = distResp == null ? "no response"
                                : (distResp.getMessage() == null ? "unknown" : distResp.getMessage());
                        // 后端业务码 10400 (模型不存在/无可用渠道) 语义透传: HTTP 404 + 信封 10400;
                        // 其余失败 (RPC 降级/未知) 按上游故障 502 + 10004
                        if (distResp != null && distResp.getCode()
                                == fun.commons.tokengateway.framework.ApiCode.NOT_FOUND.getCode()) {
                            return Mono.error(new RelayException(404,
                                    fun.commons.tokengateway.framework.ApiCode.NOT_FOUND.getCode(),
                                    "模型不存在或无可用渠道: " + reason));
                        }
                        return Mono.error(new RelayException(502,
                                "channel distribute failed: " + reason));
                    }
                    DistributeVO channel = distResp.getData();
                    // 影子双跑 (22 号 S2-W1): 旧链路照常执行, THMP resolve 只比对不执行
                    thmpShadow.compare(model, token.getTenantId(), channel);
                    return Mono.just(channel);
                })));
    }

    /**
     * 只有 MASK 动作的脱敏结果才允许改写 body.
     *
     * <p>PASS_THROUGH 分支的 sanitizedContent 是上游对入参的原样回显, 若当作脱敏结果套用,
     * 会在 extractUserContent 拿不到内容 (Anthropic block 数组) 时把消息覆盖成空串.
     */
    private static String maskedContentOf(ModerationOutcome outcome) {
        if (outcome.action() != ModerationOutcome.Action.MASK_CONTENT) {
            return null;
        }
        String sanitized = outcome.sanitizedContent();
        return sanitized == null || sanitized.isBlank() ? null : sanitized;
    }

    /**
     * 输入审核: BLOCK 抛 400, MASK 透传 sanitized + ruleCodes.
     */
    private Mono<ModerationOutcome> moderateInput(TokenValidateVO token, String userContent) {
        return moderationGate.scanInput(token.getTenantId(), token.getUserId(), userContent, null)
                .map(outcome -> {
                    if (outcome.isBlocked()) {
                        log.info("[Relay] moderation BLOCK: tenant={}, user={}, rules={}",
                                token.getTenantId(), token.getUserId(), outcome.ruleCodes());
                        throw new RelayException(400,
                                fun.commons.tokengateway.framework.ApiCode.BUSINESS_RULE_ERROR.getCode(),
                                "请求被内容安全拒绝: " + String.join(",", outcome.ruleCodes()));
                    }
                    if (outcome.action() == ModerationOutcome.Action.MASK_CONTENT) {
                        log.info("[Relay] moderation MASK: tenant={}, user={}",
                                token.getTenantId(), token.getUserId());
                    }
                    return outcome;
                });
    }

    /**
     * 预扣 (V087: 所有渠道都走 preConsume, saga 内部按 ownerType 决定双扣/单扣).
     * <p>PLATFORM → user + tenant 双扣; TENANT → user 单扣;
     * 失败抛 502, 信封 10617 (余额不足) 透传为 402.
     */
    private Mono<String> preConsumeIfNeeded(DistributeVO channel, TokenValidateVO token,
                                            String model, int estPromptTokens, int estCompletionTokens,
                                            String requestId) {
        String reqId = requestId != null ? requestId : UUID.randomUUID().toString();
        String ownerTypeStr = channel.getOwnerType() != null
                ? channel.getOwnerType().name() : fun.commons.tokengateway.contract.OwnerType.PLATFORM.name();
        return billingApi.preConsume(PreConsumeRequest.builder()
                        .tenantId(token.getTenantId())
                        .userId(token.getUserId())
                        .tokenId(token.getTokenId())
                        .channelId(channel.getChannelId())
                        .model(model)
                        .ownerType(ownerTypeStr)
                        .estimatedPromptTokens(estPromptTokens)
                        .estimatedCompletionTokens(estCompletionTokens)
                        .requestId(reqId)
                        .build())
                .map(resp -> {
                    if (resp == null || !resp.isSuccess() || resp.getData() == null
                            || !resp.getData().isSuccess()) {
                        String reason = null;
                        if (resp != null && resp.getData() != null
                                && resp.getData().getFailReason() != null) {
                            reason = resp.getData().getFailReason();
                        } else if (resp != null && resp.getMessage() != null) {
                            reason = resp.getMessage();
                        }
                        // 余额不足语义透传: HTTP 402 + 信封 10617 (对齐用户手册 §7 错误码表);
                        // 其余失败按上游故障 502 + 10004
                        if (resp != null && resp.getCode()
                                == fun.commons.tokengateway.framework.ApiCode.INSUFFICIENT_BALANCE.getCode()) {
                            throw new RelayException(402,
                                    fun.commons.tokengateway.framework.ApiCode.INSUFFICIENT_BALANCE.getCode(),
                                    "余额不足" + (reason == null ? "" : ": " + reason));
                        }
                        throw new RelayException(502, "billing preConsume failed"
                                + (reason == null ? "" : ": " + reason));
                    }
                    return resp.getData().getPreConsumeId();
                });
    }

    /**
     * 结算 (上游成功后调用).
     * <p>preConsumeId 为空 (如 count_tokens 不计费路径) 直接返回 ZERO; 否则走 settle RPC.
     * <p>fire-and-forget: 失败仅记日志, 不影响主流程.
     */
    public Mono<java.math.BigDecimal> settle(PreparedRequest prepared, int actualPromptTokens,
                             int actualCompletionTokens, int cachedTokens, int responseTimeMs) {
        if (prepared.preConsumeId() == null) {
            return Mono.just(java.math.BigDecimal.ZERO);
        }
        return billingApi.settle(SettleRequest.builder()
                        .preConsumeId(prepared.preConsumeId())
                        .actualPromptTokens(actualPromptTokens)
                        .actualCompletionTokens(actualCompletionTokens)
                        .cacheReadTokens(cachedTokens)
                        .success(true)
                        .requestId(prepared.requestId())
                        .responseTimeMs(responseTimeMs)
                        .build())
                .doOnError(e -> log.error("[Billing/settle] preConsumeId={}, err={}",
                        prepared.preConsumeId(), e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "billing settle failed: " + e.getMessage())))
                .map(resp -> {
                    if (resp == null || resp.getData() == null || resp.getData().getCreditConsumed() == null) {
                        log.warn("[Billing/settle] preConsumeId={}, resp code={}, dataNull={}",
                                prepared.preConsumeId(),
                                resp != null ? resp.getCode() : -1,
                                resp == null || resp.getData() == null);
                        return java.math.BigDecimal.ZERO;
                    }
                    java.math.BigDecimal credit = resp.getData().getCreditConsumed();
                    log.info("[Billing/settle] preConsumeId={}, creditConsumed={}",
                            prepared.preConsumeId(), credit);
                    return credit;
                });
    }

    /**
     * 退款 (上游失败/客户端取消).
     * <p>preConsumeId 为空直接 complete; 否则走 refund RPC.
     * <p>fire-and-forget: 失败仅记日志.
     */
    public Mono<Void> refund(PreparedRequest prepared, String reason) {
        if (prepared.preConsumeId() == null) {
            return Mono.empty();
        }
        return billingApi.refund(RefundRequest.builder()
                        .preConsumeId(prepared.preConsumeId())
                        .reason(reason)
                        .requestId(prepared.requestId())
                        .build())
                .doOnError(e -> log.error("[Billing/refund] preConsumeId={}, err={}",
                        prepared.preConsumeId(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /**
     * 预处理结果: token + channel + preConsumeId + requestId + 审核结果上下文.
     */
    public record PreparedRequest(TokenValidateVO token, DistributeVO channel,
                                  String preConsumeId, String requestId,
                                  String moderationSanitized, List<String> moderationRuleCodes) {
    }

    /**
     * 提取最后一条 user message content (作为审核输入).
     *
     * <p>content 为 String 直接返回; 为 Anthropic block 数组时拼接所有 text 块.
     * 找不到内容返回 null.
     */
    @SuppressWarnings("unchecked")
    public static String extractUserContent(Map<String, Object> body) {
        Map<String, Object> last = lastUserMessage(body);
        log.debug("[Relay] extractUserContent: lastUserMsg={}, bodyKeys={}",
                last != null, body == null ? "null" : body.keySet());
        if (last == null) {
            return null;
        }
        log.debug("[Relay] extractUserContent: contentType={}",
                last.get("content") == null ? "null" : last.get("content").getClass().getSimpleName());
        Object content = last.get("content");
        if (content instanceof String s) {
            return s.isEmpty() ? null : s;
        }
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof Map && "text".equals(((Map<String, Object>) b).get("type"))) {
                    Object text = ((Map<String, Object>) b).get("text");
                    if (text != null) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(text);
                    }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastUserMessage(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object last = list.get(list.size() - 1);
        if (!(last instanceof Map) || !"user".equals(((Map<String, Object>) last).get("role"))) {
            return null;
        }
        return (Map<String, Object>) last;
    }

    /**
     * MASK 动作: 把最后一条 user message 的文本替换为 sanitizedContent, 返回新 body.
     *
     * <p>content 为 block 数组时只改写首个 text 块的 text, 保留 cache_control 等同级字段;
     * messages 缺失 / 最后一条非 user / sanitizedContent 为空 → 原样返回.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> applyMask(Map<String, Object> body, String sanitizedContent) {
        if (sanitizedContent == null || sanitizedContent.isBlank()) {
            return body;
        }
        Map<String, Object> last = lastUserMessage(body);
        if (last == null) {
            return body;
        }
        Object content = last.get("content");
        Object newContent;
        if (content instanceof String) {
            newContent = sanitizedContent;
        } else if (content instanceof List<?> blocks) {
            List<Object> newBlocks = new java.util.ArrayList<>(blocks.size());
            boolean replaced = false;
            for (Object b : blocks) {
                if (!replaced && b instanceof Map
                        && "text".equals(((Map<String, Object>) b).get("type"))) {
                    Map<String, Object> newBlock = new java.util.HashMap<>((Map<String, Object>) b);
                    newBlock.put("text", sanitizedContent);
                    newBlocks.add(newBlock);
                    replaced = true;
                    continue;
                }
                newBlocks.add(b);
            }
            if (!replaced) {
                return body;
            }
            newContent = newBlocks;
        } else {
            return body;
        }

        List<Object> newList = new java.util.ArrayList<>((List<Object>) body.get("messages"));
        Map<String, Object> newLast = new java.util.HashMap<>(last);
        newLast.put("content", newContent);
        newList.set(newList.size() - 1, newLast);
        Map<String, Object> newBody = new java.util.HashMap<>(body);
        newBody.put("messages", newList);
        return newBody;
    }
}
