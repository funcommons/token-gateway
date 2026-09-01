package fun.commons.tokengateway.moderation;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Prompt scan 请求 (webflux 本地 DTO, 镜像主应用 moderation 的 ScanRequest 字段).
 *
 * <p>为避免 gateway-webflux 直接依赖 backend/moderation (类加载隔离),
 * 复制字段定义, 通过 HTTP RPC 序列化反序列化跨进程.
 */
@Getter
@Setter
@ToString
public class ScanRequest {

    private String tenantId;

    private String userId;

    private String content;

    private String systemPrompt;

    private String direction;

    private List<String> sensitiveWords;

    private String actionOnViolation;
}
