package fun.commons.tokengateway.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StreamUsageAccumulator 单元测试.
 */
@DisplayName("StreamUsageAccumulator 流式 usage 提取")
class StreamUsageAccumulatorTest {

    @Test
    @DisplayName("OpenAI 末帧 usage: prompt/completion/cached 全部取到")
    void openaiUsageFrame() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        acc.accept("data: {\"id\":\"c1\",\"choices\":[]}\n\n");
        assertThat(acc.hasUsage()).isFalse();
        acc.accept("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,"
                + "\"completion_tokens\":7,\"prompt_tokens_details\":{\"cached_tokens\":4}}}\n\n");
        acc.accept("data: [DONE]\n\n");

        assertThat(acc.hasUsage()).isTrue();
        assertThat(acc.result().promptTokens()).isEqualTo(12);
        assertThat(acc.result().completionTokens()).isEqualTo(7);
        assertThat(acc.result().cachedTokens()).isEqualTo(4);
    }

    @Test
    @DisplayName("Anthropic: message_start 取 input, message_delta 取 output (覆盖取最新)")
    void anthropicUsageFrames() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        acc.accept("event: message_start\ndata: {\"type\":\"message_start\","
                + "\"message\":{\"usage\":{\"input_tokens\":25,\"cache_read_input_tokens\":3}}}\n\n");
        acc.accept("event: content_block_delta\ndata: {\"type\":\"content_block_delta\","
                + "\"delta\":{\"text\":\"你\"}}\n\n");
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"output_tokens\":9}}\n\n");

        assertThat(acc.hasUsage()).isTrue();
        assertThat(acc.result().promptTokens()).isEqualTo(25);
        assertThat(acc.result().completionTokens()).isEqualTo(9);
        assertThat(acc.result().cachedTokens()).isEqualTo(3);
    }

    @Test
    @DisplayName("无 usage 帧: hasUsage=false, 全部 0")
    void noUsageFrame() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        acc.accept("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n");
        acc.accept("data: [DONE]\n\n");
        acc.accept("garbage-not-json");

        assertThat(acc.hasUsage()).isFalse();
        assertThat(acc.result().promptTokens()).isZero();
        assertThat(acc.result().completionTokens()).isZero();
    }

    @Test
    @DisplayName("usage 帧 JSON 损坏: 静默忽略不抛")
    void corruptUsageFrame() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        acc.accept("data: {\"usage\":{broken\n\n");
        assertThat(acc.hasUsage()).isFalse();
    }

    /**
     * 回归测试 (#27): MiniMax / 兼容 Anthropic 上游在 message_delta 累计 input_tokens
     * (cumulative, latest wins),且通常 message_start 不含 input_tokens 字段.
     * 修复前 parseAnthropicUsage 完全忽略 message_delta 的 input_tokens/cache_*,
     * 导致 prompt_tokens=0, cached_tokens=0 (MiniMax 实测 input=1184, cached=259456).
     */
    @Test
    @DisplayName("回归 #27: Anthropic message_delta 含 input_tokens 必须被读取 (覆盖式 latest wins)")
    void anthropicMessageDeltaInputTokensRead() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        // message_start 在 MiniMax 上游可能不含 usage 字段 (或 input_tokens=0)
        acc.accept("event: message_start\ndata: {\"type\":\"message_start\","
                + "\"message\":{\"id\":\"x\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[],\"model\":\"MiniMax-M3\",\"usage\":{}}}\n\n");
        // 第一个 message_delta: cumulative input_tokens=101, output_tokens=10
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":null},"
                + "\"usage\":{\"input_tokens\":101,\"output_tokens\":10,"
                + "\"cache_read_input_tokens\":5000}}\n\n");
        // 第二个 message_delta: input_tokens 累计到 1184, output=850
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"tool_use\"},"
                + "\"usage\":{\"input_tokens\":1184,\"output_tokens\":850,"
                + "\"cache_read_input_tokens\":259456}}\n\n");

        assertThat(acc.hasUsage()).isTrue();
        // 关键断言: input_tokens latest=1184, 不应是 0
        assertThat(acc.result().promptTokens()).isEqualTo(1184);
        assertThat(acc.result().completionTokens()).isEqualTo(850);
        // 关键断言: cache_read_input_tokens latest=259456, 不应是 0
        assertThat(acc.result().cachedTokens()).isEqualTo(259456);
    }

    @Test
    @DisplayName("Anthropic message_delta 含 cache_creation_input_tokens: 累加到 cachedTokens")
    void anthropicMessageDeltaCacheCreationAccumulated() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50,"
                + "\"cache_creation_input_tokens\":1024,\"cache_read_input_tokens\":2048}}\n\n");

        assertThat(acc.hasUsage()).isTrue();
        assertThat(acc.result().promptTokens()).isEqualTo(100);
        assertThat(acc.result().completionTokens()).isEqualTo(50);
        // cache_creation + cache_read 累加
        assertThat(acc.result().cachedTokens()).isEqualTo(1024 + 2048);
    }

    @Test
    @DisplayName("Anthropic 多 message_delta 累计: 多次 cache_creation_input_tokens 累加")
    void anthropicMessageDeltaCacheCreationMultipleDelta() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"cache_creation_input_tokens\":100}}\n\n");
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"cache_creation_input_tokens\":200}}\n\n");
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"cache_creation_input_tokens\":50}}\n\n");

        assertThat(acc.hasUsage()).isTrue();
        assertThat(acc.result().cachedTokens()).isEqualTo(350);
    }

    @Test
    @DisplayName("Anthropic 多 message_delta 累计: cache_read_input_tokens 取 latest (覆盖)")
    void anthropicMessageDeltaCacheReadLatestWins() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"cache_read_input_tokens\":100}}\n\n");
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"cache_read_input_tokens\":500}}\n\n");
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"cache_read_input_tokens\":50}}\n\n");

        assertThat(acc.hasUsage()).isTrue();
        // 覆盖式, latest=50
        assertThat(acc.result().cachedTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("Anthropic 标准 message_start 含 input_tokens: 仍能正确读取")
    void anthropicStandardMessageStart() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        acc.accept("event: message_start\ndata: {\"type\":\"message_start\","
                + "\"message\":{\"usage\":{\"input_tokens\":100,\"cache_read_input_tokens\":30}}}\n\n");
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"output_tokens\":50}}\n\n");

        assertThat(acc.hasUsage()).isTrue();
        assertThat(acc.result().promptTokens()).isEqualTo(100);
        assertThat(acc.result().completionTokens()).isEqualTo(50);
        assertThat(acc.result().cachedTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("Anthropic message_start 缺 input_tokens + message_delta 提供: 正确从 delta 取")
    void anthropicStartMissingInputDeltaProvidesIt() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        // message_start 完全没有 usage.input_tokens
        acc.accept("event: message_start\ndata: {\"type\":\"message_start\","
                + "\"message\":{\"id\":\"x\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[],\"model\":\"claude-3-5-sonnet\"}}\n\n");
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"input_tokens\":42,\"output_tokens\":10,"
                + "\"cache_read_input_tokens\":5}}\n\n");

        assertThat(acc.hasUsage()).isTrue();
        assertThat(acc.result().promptTokens()).isEqualTo(42);
        assertThat(acc.result().cachedTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("兜底: 部分非标准上游把 usage 直接放在 chunk 顶层 (无 type)")
    void anthropicUsageAtChunkTopLevel() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        acc.accept("data: {\"usage\":{\"input_tokens\":50,\"output_tokens\":20}}\n\n");

        assertThat(acc.hasUsage()).isTrue();
        assertThat(acc.result().promptTokens()).isEqualTo(50);
        assertThat(acc.result().completionTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("兜底: message_delta + 顶层 usage 同时存在, 不冲突")
    void anthropicBothLevelsCoexist() {
        StreamUsageAccumulator acc = new StreamUsageAccumulator();
        // 标准 message_delta
        acc.accept("event: message_delta\ndata: {\"type\":\"message_delta\","
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50,"
                + "\"cache_read_input_tokens\":10}}\n\n");
        // 顶层 usage 兜底
        acc.accept("data: {\"usage\":{\"input_tokens\":200,\"output_tokens\":80}}\n\n");

        assertThat(acc.hasUsage()).isTrue();
        // type=null 兜底分支会覆盖 type=message_delta 已设值 (latest 帧为准)
        assertThat(acc.result().promptTokens()).isEqualTo(200);
        assertThat(acc.result().completionTokens()).isEqualTo(80);
    }
}
