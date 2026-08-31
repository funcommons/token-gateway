package fun.commons.tokengateway.ratelimit;

import fun.commons.tokengateway.config.RateLimitProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisWindowRateLimiter 容器契约测试 (真实 Redis 验 Lua 滑动窗口语义).
 *
 * <p>Docker 不可用时自动跳过 (disabledWithoutDocker).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("RedisWindowRateLimiter (Testcontainers Redis)")
class RedisWindowRateLimiterContainerTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private static RedisWindowRateLimiter limiter(int limit, int windowSeconds) {
        RateLimitProperties props = new RateLimitProperties();
        props.setLimit(limit);
        props.setWindowSeconds(windowSeconds);
        return new RedisWindowRateLimiter(redis, props);
    }

    @Test
    @DisplayName("窗口内未超限 → 放行且计数递增")
    void underLimitAllowed() {
        String key = UUID.randomUUID().toString();
        RedisWindowRateLimiter limiter = limiter(3, 60);

        StepVerifier.create(limiter.tryAcquire(key))
                .assertNext(d -> {
                    assertThat(d.allowed()).isTrue();
                    assertThat(d.currentCount()).isEqualTo(1);
                })
                .verifyComplete();
        StepVerifier.create(limiter.tryAcquire(key))
                .assertNext(d -> {
                    assertThat(d.allowed()).isTrue();
                    assertThat(d.currentCount()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("超限 → 拒绝且 retryAfter > 0; 计数不回落")
    void overLimitDenied() {
        String key = UUID.randomUUID().toString();
        RedisWindowRateLimiter limiter = limiter(2, 60);

        limiter.tryAcquire(key).block();
        limiter.tryAcquire(key).block();
        StepVerifier.create(limiter.tryAcquire(key))
                .assertNext(d -> {
                    assertThat(d.allowed()).isFalse();
                    assertThat(d.currentCount()).isEqualTo(3);
                    assertThat(d.retryAfterSeconds()).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("窗口滑出 → 重新放行")
    void windowSlides() {
        String key = UUID.randomUUID().toString();
        RedisWindowRateLimiter limiter = limiter(1, 1);

        assertThat(limiter.tryAcquire(key).block().allowed()).isTrue();
        assertThat(limiter.tryAcquire(key).block().allowed()).isFalse();
        // 等最旧条目滑出 1s 窗口
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(limiter.tryAcquire(key).block().allowed()).isTrue();
    }

    @Test
    @DisplayName("不同 key 互不影响")
    void keysIsolated() {
        String keyA = UUID.randomUUID().toString();
        String keyB = UUID.randomUUID().toString();
        RedisWindowRateLimiter limiter = limiter(1, 60);

        assertThat(limiter.tryAcquire(keyA).block().allowed()).isTrue();
        assertThat(limiter.tryAcquire(keyA).block().allowed()).isFalse();
        assertThat(limiter.tryAcquire(keyB).block().allowed()).isTrue();
    }

    @Test
    @DisplayName("Redis 不可达 → fail-open 放行")
    void redisDownFailOpen() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1));
        dead.afterPropertiesSet();
        try {
            RedisWindowRateLimiter limiter =
                    new RedisWindowRateLimiter(new ReactiveStringRedisTemplate(dead), propsOf(1, 60));
            StepVerifier.create(limiter.tryAcquire(UUID.randomUUID().toString()))
                    .assertNext(d -> assertThat(d.allowed()).isTrue())
                    .verifyComplete();
        } finally {
            dead.destroy();
        }
    }

    private static RateLimitProperties propsOf(int limit, int windowSeconds) {
        RateLimitProperties props = new RateLimitProperties();
        props.setLimit(limit);
        props.setWindowSeconds(windowSeconds);
        return props;
    }
}
