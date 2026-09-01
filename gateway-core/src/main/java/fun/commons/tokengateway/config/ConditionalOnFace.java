package fun.commons.tokengateway.config;

import fun.commons.tokengateway.spi.config.Face;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

/**
 * face 部署分组装配条件 (token-gateway.face = llm | task | all, 默认 all).
 *
 * <p>设计方案 §9: 同 jar 异配置部署分组 —— face=llm 实例只装 LLM 面 (无数据库无本地盘),
 * face=task 实例只装任务面 (挂数据库 + 资源缓存盘), face=all 单组合跑.
 * face 值非法时启动 fail-fast (配置错误趁早暴露).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnFace.OnFaceCondition.class)
public @interface ConditionalOnFace {

    /** 装配本面所需的 face 值 (all 恒装配). */
    Face[] value();

    class OnFaceCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String raw = context.getEnvironment().getProperty("token-gateway.face", "all");
            Face current;
            try {
                current = Face.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "token-gateway.face 非法值: '" + raw + "' (允许 llm | task | all)");
            }
            if (current == Face.ALL) {
                return true;
            }
            Map<String, Object> attrs =
                    metadata.getAnnotationAttributes(ConditionalOnFace.class.getName());
            for (Face f : (Face[]) attrs.get("value")) {
                if (f == current) {
                    return true;
                }
            }
            return false;
        }
    }
}
