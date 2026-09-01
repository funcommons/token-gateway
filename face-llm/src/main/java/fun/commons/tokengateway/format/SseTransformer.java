package fun.commons.tokengateway.format;

import org.springframework.http.codec.ServerSentEvent;

import java.util.List;
import java.util.Map;

/**
 * SSE chunk 转换器 (从 backend/gateway 复制).
 * <p>跨协议 SSE 流: 上游 OpenAI chunk (Map) → 客户端期望的 SSE 事件序列.
 * <p>实现需有状态: 跟踪 message_id / content_block index / 累计 usage.
 */
public interface SseTransformer {

    List<ServerSentEvent<String>> transform(Map<String, Object> openAiChunk);

    List<ServerSentEvent<String>> onComplete();
}
