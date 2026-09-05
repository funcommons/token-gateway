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
    private int cacheCreationTokens;
    private int cacheReadTokens;
    private boolean success;
    private String requestId;
    private String upstreamRequestId;
    private int responseTimeMs;
    private String errorCode;
    private String errorMessage;

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
