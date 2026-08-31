package fun.commons.tokengateway.idempotency;

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

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisIdempotencyStore 容器契约测试 (真实 Redis 验 SET NX PX 语义).
 *
 * <p>Docker 不可用时自动跳过 (disabledWithoutDocker).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("RedisIdempotencyStore (Testcontainers Redis)")
class RedisIdempotencyStoreContainerTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisIdempotencyStore store;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        store = new RedisIdempotencyStore(new ReactiveStringRedisTemplate(connectionFactory));
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("首次占位成功, 重复占位失败")
    void acquireThenDuplicate() {
        String key = "idem:" + UUID.randomUUID();

        StepVerifier.create(store.tryAcquire(key, Duration.ofMinutes(1)))
                .expectNext(true).verifyComplete();
        StepVerifier.create(store.tryAcquire(key, Duration.ofMinutes(1)))
                .expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("release 后可重新占位")
    void releaseThenReacquire() {
        String key = "idem:" + UUID.randomUUID();

        assertThat(store.tryAcquire(key, Duration.ofMinutes(1)).block()).isTrue();
        StepVerifier.create(store.release(key)).verifyComplete();
        StepVerifier.create(store.tryAcquire(key, Duration.ofMinutes(1)))
                .expectNext(true).verifyComplete();
    }

    @Test
    @DisplayName("TTL 过期后可重新占位")
    void ttlExpiryReacquire() {
        String key = "idem:" + UUID.randomUUID();

        assertThat(store.tryAcquire(key, Duration.ofSeconds(1)).block()).isTrue();
        assertThat(store.tryAcquire(key, Duration.ofSeconds(1)).block()).isFalse();
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        StepVerifier.create(store.tryAcquire(key, Duration.ofSeconds(1)))
                .expectNext(true).verifyComplete();
    }

    @Test
    @DisplayName("Redis 不可达 → tryAcquire fail-open true, release 吞错")
    void redisDownFailOpen() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1));
        dead.afterPropertiesSet();
        try {
            RedisIdempotencyStore deadStore =
                    new RedisIdempotencyStore(new ReactiveStringRedisTemplate(dead));
            StepVerifier.create(deadStore.tryAcquire("idem:x", Duration.ofSeconds(1)))
                    .expectNext(true).verifyComplete();
            StepVerifier.create(deadStore.release("idem:x")).verifyComplete();
        } finally {
            dead.destroy();
        }
    }
}
