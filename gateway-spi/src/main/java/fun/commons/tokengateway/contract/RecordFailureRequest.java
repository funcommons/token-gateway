package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordFailureRequest {
    private String tenantId;
    private String errorCode;
    private String errorMessage;
    /** 触发失败的 API Key ID (亲和清除定位用) */
    private String apiKeyId;
    /** 失败请求的模型编码 (亲和 key 的 model 维度) */
    private String model;
}
