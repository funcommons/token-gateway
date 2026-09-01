package fun.commons.tokengateway.thmp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THMP 契约面客户端 (18 号 §3.1 POST /v1/candidates/resolve, HMAC 四头验签).
 *
 * <p>22 号 S2-W1 影子双跑: 旧 distribute 照常出渠道, THMP resolve 并行解析, 结果只比对不执行 (W3 才切流).
 * 信封复用 {@link fun.commons.tokengateway.framework.ApiResponse} (snake 传输兼容, trace_id 已映射).
 *
 * <p>签名: X-Access-Key/X-Timestamp/X-Nonce/X-Signature, 公式见 {@link ThmpSignature} (移植自 fwk4j).
 * 请求体自行序列化 (MD5 覆盖精确字节, 禁 WebClient 内部序列化再摘要的双份漂移).
 *
 * <p>装配走 {@link ThmpGatewayConfiguration} (gateway.thmp.enabled 条件化, 关闭态不装配).
 */
@Slf4j
@RequiredArgsConstructor
public class ThmpContractClient {

    /** 契约面 resolve 路径 (fwk4j-signature 对 stringToSign 用不含 query 的请求路径) */
    static final String RESOLVE_PATH = "/v1/candidates/resolve";

    private final WebClient.Builder webClientBuilder;
    private final ThmpContractProperties props;

    /**
     * 解析候选. HTTP 非 200 / 网络 / 超时 → Mono.error; 信封 code 非 0 原样透传 (调用方判 isSuccess).
     */
    public Mono<fun.commons.tokengateway.framework.ApiResponse<ResolveResult>> resolve(
            String modelCode, String tenantId) {
        ResolveRequest body = new ResolveRequest(modelCode, tenantId == null || tenantId.isBlank()
                ? "0" : tenantId);
        byte[] bodyBytes;
        try {
            bodyBytes = ThmpContractJson.MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("resolve 请求体序列化失败", e));
        }
        String bodyJson = new String(bodyBytes, StandardCharsets.UTF_8);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String sts = ThmpSignature.buildStringToSign("POST", RESOLVE_PATH, timestamp, nonce,
                ThmpSignature.md5Hex(bodyBytes));
        String signature = ThmpSignature.sign(props.getSecret(), sts);

        return webClientBuilder.build().post()
                .uri(props.getBaseUrl() + RESOLVE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Access-Key", props.getClientId())
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .bodyValue(bodyJson)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<
                        fun.commons.tokengateway.framework.ApiResponse<ResolveResult>>() {})
                .doOnError(e -> log.warn("[THMP-CLIENT] resolve 失败: model={}, err={}",
                        modelCode, e instanceof WebClientResponseException w
                                ? w.getStatusCode() + " " + w.getResponseBodyAsString()
                                : e.getMessage()));
    }

    /**
     * resolve 请求体 (18 号 §3.1; affinity/exclude 影子期不用, 结构预留).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolveRequest(String model_code, String tenant_id) {
    }

    /**
     * 解析结果 (18 号 §3.1 冻结结构; 字段名即 wire 名, 记录组件天然 snake).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolveResult(List<Candidate> candidates, List<Blocked> blocked,
                                boolean affinity_hit, boolean cache_hit) {

        public boolean hasCandidates() {
            return candidates != null && !candidates.isEmpty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(String procurement_model_id, String channel_id, int priority,
                            String upstream_base_url, String protocol_type, String key_select_mode,
                            List<KeyEntry> keys, Map<String, Object> model_params, Integer capacity,
                            Cost cost) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyEntry(String key_id, String key_cipher_tenant) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cost(String cost_mode, String currency, List<Object> cost_items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Blocked(String procurement_model_id, String reason) {
    }
}
