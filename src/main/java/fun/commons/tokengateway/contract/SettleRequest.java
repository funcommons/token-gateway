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
}
