package fun.commons.tokengateway.config;

import fun.commons.tokengateway.spi.config.Face;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;


import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OnFaceCondition 测试: 默认 all 恒装配 / 按面匹配 / 大小写与空白容错 / 非法值 fail-fast.
 */
class ConditionalOnFaceTest {

    private final ConditionalOnFace.OnFaceCondition condition = new ConditionalOnFace.OnFaceCondition();

    private static org.springframework.context.annotation.ConditionContext ctx(String faceValue) {
        MockEnvironment env = new MockEnvironment();
        if (faceValue != null) {
            env.setProperty("token-gateway.face", faceValue);
        }
        org.springframework.context.annotation.ConditionContext context =
                mock(org.springframework.context.annotation.ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);
        return context;
    }

    private static org.springframework.core.type.AnnotatedTypeMetadata meta(Face... faces) {
        org.springframework.core.type.AnnotatedTypeMetadata metadata =
                mock(org.springframework.core.type.AnnotatedTypeMetadata.class);
        when(metadata.getAnnotationAttributes(ConditionalOnFace.class.getName()))
                .thenReturn(Map.of("value", faces));
        return metadata;
    }

    @Test
    void absentPropertyDefaultsToAllAndAlwaysMatches() {
        assertThat(condition.matches(ctx(null), meta(Face.LLM))).isTrue();
        assertThat(condition.matches(ctx(null), meta(Face.TASK))).isTrue();
    }

    @Test
    void explicitAllMatchesAnyRequirement() {
        assertThat(condition.matches(ctx("all"), meta(Face.LLM))).isTrue();
        assertThat(condition.matches(ctx("ALL"), meta(Face.TASK))).isTrue();
    }

    @Test
    void faceValueMatchAndMismatch() {
        assertThat(condition.matches(ctx("llm"), meta(Face.LLM))).isTrue();
        assertThat(condition.matches(ctx("llm"), meta(Face.LLM, Face.TASK))).isTrue();
        assertThat(condition.matches(ctx("llm"), meta(Face.TASK))).isFalse();
        assertThat(condition.matches(ctx("task"), meta(Face.LLM))).isFalse();
    }

    @Test
    void valueIsTrimmedAndCaseInsensitive() {
        assertThat(condition.matches(ctx("  Llm "), meta(Face.LLM))).isTrue();
    }

    @Test
    void illegalValueFailsFast() {
        assertThatThrownBy(() -> condition.matches(ctx("bogus"), meta(Face.LLM)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bogus").hasMessageContaining("llm | task | all");
    }

    @Test
    void faceEnumHasThreeDeployGroups() {
        assertThat(Face.values()).containsExactlyInAnyOrder(Face.LLM, Face.TASK, Face.ALL);
    }
}
