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
    /** 上游真实 HTTP 状态码 (硬错误=上游响应状态; 软失败=错误载荷 status 字段, 缺省 502);
     *  无 HTTP 语义的失败 (errorCode=UPSTREAM_ERROR, 如超时/连接拒绝) 为 null. 向后兼容可选字段. */
    private Integer upstreamStatus;
    /** 本次上游调用耗时 (毫秒, 网关侧 elapsed). 向后兼容可选字段. */
    private Long latencyMs;
}
