package fun.commons.tokengateway.config;

import fun.commons.tokengateway.config.ErrorContractProperties.ErrorShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ErrorContractProperties 单测 (issue #24): 错误形状默认值 + 透传白名单默认表与查表语义.
 */
@DisplayName("ErrorContractProperties")
class ErrorContractPropertiesTest {

    @Test
    @DisplayName("默认: envelope 形状 (现契约, 默认关是硬约束) + 默认白名单六条 (10612 自 issue #37 起入默认)")
    void defaults() {
        var props = new ErrorContractProperties();
        assertThat(props.getErrorShape()).isEqualTo(ErrorShape.ENVELOPE);
        assertThat(props.isOpenAiShape()).isFalse();
        assertThat(props.getErrorPassthroughCodes())
                .containsEntry(4090, 403)
                .containsEntry(10601, 402)
                .containsEntry(10602, 404)
                .containsEntry(10603, 404)
                .containsEntry(10402, 409)
                .containsEntry(10612, 403)
                .hasSize(6);
    }

    @Test
    @DisplayName("openai 形状切换后 isOpenAiShape=true")
    void openAiShape() {
        var props = new ErrorContractProperties();
        props.setErrorShape(ErrorShape.OPENAI);
        assertThat(props.isOpenAiShape()).isTrue();
    }

    @Test
    @DisplayName("passthroughStatusOf: 命中回透传状态, 未命中/null 回 null (走既有映射)")
    void passthroughLookup() {
        var props = new ErrorContractProperties();
        assertThat(props.passthroughStatusOf(4090)).isEqualTo(403);
        assertThat(props.passthroughStatusOf(10601)).isEqualTo(402);
        assertThat(props.passthroughStatusOf(10602)).isEqualTo(404);
        assertThat(props.passthroughStatusOf(10603)).isEqualTo(404);
        assertThat(props.passthroughStatusOf(10402)).isEqualTo(409);
        assertThat(props.passthroughStatusOf(10612)).isEqualTo(403);
        assertThat(props.passthroughStatusOf(9999)).isNull();
        assertThat(props.passthroughStatusOf(20199)).isNull();
        assertThat(props.passthroughStatusOf(null)).isNull();
    }

    @Test
    @DisplayName("yml 覆盖同键生效 (按键合并语义): 覆盖 4090 状态, 其余默认保留")
    void overrideKey() {
        var props = new ErrorContractProperties();
        props.getErrorPassthroughCodes().put(4090, 422);
        assertThat(props.passthroughStatusOf(4090)).isEqualTo(422);
        assertThat(props.passthroughStatusOf(10601)).isEqualTo(402);
    }

    @Test
    @DisplayName("Binder 合并实测: error-shape=openai 生效 + 同键覆盖 4090→401 + 异键新增 7777→410 + 默认键保留")
    void binderMergeOverriddenAndNewKeys() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("gateway.error-shape", "openai");
        env.setProperty("gateway.error-passthrough-codes.4090", "401");
        env.setProperty("gateway.error-passthrough-codes.7777", "410");

        var props = new ErrorContractProperties();
        Binder.get(env).bind("gateway", Bindable.ofInstance(props));

        // 枚举 relaxed binding: "openai" → OPENAI
        assertThat(props.getErrorShape()).isEqualTo(ErrorShape.OPENAI);
        assertThat(props.isOpenAiShape()).isTrue();
        // 同键覆盖: 4090 用配置值 401 压过默认 403
        assertThat(props.passthroughStatusOf(4090)).isEqualTo(401);
        // 异键新增: 7777 → 410
        assertThat(props.passthroughStatusOf(7777)).isEqualTo(410);
        // 默认键保留 (合并语义, 不可经配置删除)
        assertThat(props.passthroughStatusOf(10601)).isEqualTo(402);
        assertThat(props.passthroughStatusOf(10602)).isEqualTo(404);
        assertThat(props.passthroughStatusOf(10603)).isEqualTo(404);
        assertThat(props.passthroughStatusOf(10402)).isEqualTo(409);
        assertThat(props.passthroughStatusOf(10612)).isEqualTo(403);
        assertThat(props.getErrorPassthroughCodes()).hasSize(7);
    }
}
