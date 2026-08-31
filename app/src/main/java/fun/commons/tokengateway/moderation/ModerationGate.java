package fun.commons.tokengateway.moderation;

import fun.commons.tokengateway.rpc.HttpModerationApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Moderation 网关层 Gate (webflux 版).
 *
 * <p>薄包装: 调 {@link HttpModerationApi} 走 RPC, 失败 fail-open 放行.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationGate {

    private final HttpModerationApi httpModerationApi;

    /**
     * 对 LLM 输入做前置扫描. RPC 异常 → fail-open PASS_THROUGH (由 HttpModerationApi.onErrorResume 处理).
     *
     * @param tenantId     租户 ID
     * @param userId       用户 ID
     * @param prompt       用户输入内容 (多轮合并后的最后一条 user message)
     * @param systemPrompt system 提示词 (可空)
     */
    public Mono<ModerationOutcome> scanInput(String tenantId, String userId,
                                             String prompt, String systemPrompt) {
        ScanRequest req = new ScanRequest();
        req.setTenantId(tenantId);
        req.setUserId(userId);
        req.setContent(prompt == null ? "" : prompt);
        req.setSystemPrompt(systemPrompt);
        req.setDirection("INPUT");
        return httpModerationApi.scan(req);
    }
}
