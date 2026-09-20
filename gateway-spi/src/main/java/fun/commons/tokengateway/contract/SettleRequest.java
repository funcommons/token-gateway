package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettleRequest {
    private String preConsumeId;
    private int actualPromptTokens;
    private int actualCompletionTokens;

    /**
     * 缓存写入 tokens (Anthropic cache_creation_input_tokens). 既有字段, issue #26 起由
     * 主链路真实填充 (此前恒 0 缺省); int 缺省 0 语义不变, 与 cacheReadTokens 合计仍含于
     * actualPromptTokens (Anthropic 口径), 拆分只发生在细分字段.
     */
    private int cacheCreationTokens;

    private int cacheReadTokens;

    /**
     * 细分留痕维 (issue #26 可选增量, 不参与计价; null = 无源数据不造数,
     * 旧计费后端忽略未知字段). OpenAI completion_tokens_details.reasoning_tokens
     * (completion 子集); Anthropic 原生无此维恒 null.
     */
    private Long reasoningTokens;

    /**
     * 细分留痕维 (issue #26 可选增量): OpenAI prompt/completion 两侧
     * audio_tokens 求和的留痕维; null = 无源数据不造数.
     */
    private Long audioTokens;

    private boolean success;
    private String requestId;
    private String upstreamRequestId;
    private int responseTimeMs;
    private String errorCode;
    private String errorMessage;

    /**
     * 结算主体（token 侧 user_id，③⑦ 对账 owner 桥）: 计费后端落 tg_settlement_outbox
     * owner_party_id 供差错池日报/对账按户核销; 缺省 null = 旧版本语义（后端落 0 占位）.
     */
    private Long ownerPartyId;

    /**
     * 失败尝试明细 (G4, M1 版本化向后兼容增量: 可选字段, 旧计费后端忽略未知字段).
     * <p>每个可重试失败尝试一条 (不含最终成功结算的 MAIN 笔); 能力面按
     * {@code billed=true} 记 LOSS (路由损耗) 分录, 供毛利报表 (产品原型 §11.7/§14.2).
     */
    @Builder.Default
    private java.util.List<AttemptDetail> attempts = new java.util.ArrayList<>();

    /** 单次失败尝试明细. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttemptDetail {
        /** 尝试序号 (1 起, 与 failover.attempt 对齐). */
        private int sequence;
        /** 该尝试命中的渠道. */
        private String channelId;
        /** 该尝试模型 (model_mapping 重映射后的上游模型名). */
        private String model;
        /** 错误归类: HTTP_&lt;status&gt; / UPSTREAM_ERROR (UpstreamErrorPolicy 口径). */
        private String errorClass;
        /** 上游已计费标记: 该失败尝试上游是否已扣量 (软失败 200+错误载荷 = true). */
        private boolean billed;
        /** 该尝试用量 (上游未回报为 null). */
        private Integer promptTokens;
        private Integer completionTokens;
    }
}
