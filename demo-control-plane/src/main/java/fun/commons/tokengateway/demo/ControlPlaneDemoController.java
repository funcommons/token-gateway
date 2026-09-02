package fun.commons.tokengateway.demo;

import fun.commons.tokengateway.contract.DistributeRequest;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.OwnerType;
import fun.commons.tokengateway.contract.PreConsumeRequest;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.contract.SettleVO;
import fun.commons.tokengateway.contract.TokenValidateRequest;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.moderation.ScanRequest;
import fun.commons.tokengateway.moderation.ScanResult;
import fun.commons.tokengateway.spi.model.ChatModelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 控制层能力面桩 (联调/冒烟): 实现网关 rpc/* 消费的全部端点, 上游路由指向 token-mock.
 *
 * <p>演练凭证: {@code sk-demo-*} 正常; {@code sk-poor} 余额不足 (10617);
 * {@code sk-banned} 令牌无效 (10202); 内容含 "违禁词" 触发审核 BLOCK (10106).
 * 内部鉴权头不校验 (demo 桩, 仅联调环境).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ControlPlaneDemoController {

    private final DemoBillingLedger ledger;

    /** token-mock 地址 (docker run -p 9999:9999 ghcr.io/funcommons/token-mock). */
    @Value("${demo.upstream-mock:http://localhost:9999}")
    private String upstreamMock;

    // ---------- 凭证校验 ----------

    @PostMapping("/api/v1/internal/tokens/validate")
    public ApiResponse<TokenValidateVO> validate(@RequestBody TokenValidateRequest req) {
        String key = req.getApiKey() == null ? "" : req.getApiKey();
        if (!key.startsWith("sk-") || key.startsWith("sk-banned")) {
            return ApiResponse.fail(ApiCode.TOKEN_INVALID, "令牌无效");
        }
        TokenValidateVO vo = new TokenValidateVO();
        vo.setValid(true);
        vo.setTokenId("demo-token");
        vo.setUserId(key.startsWith("sk-poor") ? "demo-poor" : "demo-user");
        vo.setTenantId("demo-tenant");
        vo.setGroupId("default");
        vo.setModelAllowed(true);
        vo.setIpAllowed(true);
        vo.setRemainQuota(key.startsWith("sk-poor")
                ? new java.math.BigDecimal("0.0001") : new java.math.BigDecimal("100"));
        return ApiResponse.success(vo);
    }

    // ---------- 路由分发 ----------

    @PostMapping("/api/v1/internal/channels/distribute")
    public ApiResponse<DistributeVO> distribute(@RequestBody DistributeRequest req) {
        String model = req.getModel() == null ? "" : req.getModel();
        DistributeVO vo = new DistributeVO();
        vo.setChannelId("demo-ch");
        vo.setType(1);
        vo.setOwnerType(OwnerType.PLATFORM);
        if (model.startsWith("claude-")) {
            vo.setBaseUrl(upstreamMock + "/anthropic");
            vo.setApiKey("sk-mock-anthropic-1234567890abcdef");
            vo.setProtocol("anthropic");
        } else if (model.startsWith("vid-")) {
            // 任务面上游: token-mock 视频异步任务 (Worker 脚本 token-mock-v1 适配)
            vo.setBaseUrl(upstreamMock + "/openai");
            vo.setApiKey("sk-mock-openai-1234567890abcdef");
            vo.setProtocol("openai");
        } else if (model.startsWith("gpt-") || model.startsWith("deepseek-")
                || model.startsWith("text-embedding") || model.startsWith("dall-e")) {
            vo.setBaseUrl(upstreamMock + "/openai");
            vo.setApiKey("sk-mock-openai-1234567890abcdef");
            vo.setProtocol("openai");
        } else {
            return ApiResponse.fail(ApiCode.NOT_FOUND, "模型不存在或无可用渠道: " + model);
        }
        return ApiResponse.success(vo);
    }

    @PostMapping("/api/v1/internal/channels/{channelId}/record-success")
    public ApiResponse<Void> recordSuccess(@PathVariable String channelId) {
        return ApiResponse.success();
    }

    @PostMapping("/api/v1/internal/channels/{channelId}/record-failure")
    public ApiResponse<Void> recordFailure(@PathVariable String channelId) {
        return ApiResponse.success();
    }

    // ---------- 计费 saga ----------

    @PostMapping("/api/v1/internal/billing/pre-consume")
    public ApiResponse<PreConsumeVO> preConsume(@RequestBody PreConsumeRequest req) {
        PreConsumeVO vo = ledger.preConsume(req);
        if (!vo.isSuccess()) {
            return ApiResponse.fail(ApiCode.INSUFFICIENT_BALANCE, vo.getFailReason());
        }
        return ApiResponse.success(vo);
    }

    @PostMapping("/api/v1/internal/billing/settle")
    public ApiResponse<SettleVO> settle(@RequestBody SettleRequest req) {
        return ApiResponse.success(ledger.settle(req));
    }

    @PostMapping("/api/v1/internal/billing/refund")
    public ApiResponse<Void> refund(@RequestBody RefundRequest req) {
        ledger.refund(req);
        return ApiResponse.success();
    }

    // ---------- 内容审核 ----------

    @PostMapping("/v1/internal/moderation/scan")
    public ApiResponse<ScanResult> scan(@RequestBody ScanRequest req) {
        ScanResult result = new ScanResult();
        String content = req.getContent() == null ? "" : req.getContent();
        if (content.contains("违禁词")) {
            result.setPassed(false);
            result.setActionTaken("BLOCK");
            ScanResult.Match match = new ScanResult.Match();
            match.setRuleCode("DEMO_BANNED_WORD");
            result.setMatches(List.of(match));
        } else {
            result.setPassed(true);
            result.setActionTaken("PASS");
        }
        result.setSanitizedContent(content);
        return ApiResponse.success(result);
    }

    // ---------- 模型目录 / 日志 ----------

    @GetMapping("/api/v1/internal/chat-models")
    public ApiResponse<List<ChatModelVO>> chatModels(@RequestParam(required = false) String groupId) {
        return ApiResponse.success(List.of(
                new ChatModelVO("gpt-4o-mini", "demo"),
                new ChatModelVO("claude-sonnet-4-5", "demo"),
                new ChatModelVO("text-embedding-3-small", "demo"),
                new ChatModelVO("vid-mock-1", "demo")));
    }

    @PostMapping("/api/v1/internal/access-log/record")
    public ApiResponse<Void> accessLog(@RequestBody Map<String, Object> entry) {
        return ApiResponse.success();
    }

    // ---------- demo 管理 (冒烟脚本断言用) ----------

    /** 对账视图: 未闭环预扣应为空; 余额表. */
    @GetMapping("/demo/state")
    public Map<String, Object> state() {
        return Map.of(
                "openHolds", ledger.openHolds(),
                "balances", ledger.balances());
    }

    @PostMapping("/demo/reset")
    public Map<String, String> reset() {
        ledger.reset();
        return Map.of("reset", "ok");
    }
}
