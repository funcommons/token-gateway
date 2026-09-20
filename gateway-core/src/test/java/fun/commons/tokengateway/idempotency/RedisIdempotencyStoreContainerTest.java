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
 * RedisIdempotencyStore 容器契约测试 (真实 Redis 验 SET NX PX 占位 + 首响缓存语义, issue #28).
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
    private static ReactiveStringRedisTemplate template;
    private static RedisIdempotencyStore store;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        template = new ReactiveStringRedisTemplate(connectionFactory);
        store = new RedisIdempotencyStore(template);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private static String freshKey() {
        return "idem:" + UUID.randomUUID();
    }

    @Test
    @DisplayName("首次占位成功, 重复占位失败")
    void acquireThenDuplicate() {
        String key = freshKey();

        StepVerifier.create(store.tryAcquire(key, Duration.ofMinutes(1)))
                .expectNext(true).verifyComplete();
        StepVerifier.create(store.tryAcquire(key, Duration.ofMinutes(1)))
                .expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("release 后可重新占位")
    void releaseThenReacquire() {
        String key = freshKey();

        assertThat(store.tryAcquire(key, Duration.ofMinutes(1)).block()).isTrue();
        StepVerifier.create(store.release(key)).verifyComplete();
        StepVerifier.create(store.tryAcquire(key, Duration.ofMinutes(1)))
                .expectNext(true).verifyComplete();
    }

    @Test
    @DisplayName("TTL 过期后可重新占位")
    void ttlExpiryReacquire() {
        String key = freshKey();

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
    @DisplayName("三态: 无键/占位 → findResponse 空; saveResponse 后命中且字段保真")
    void threeStatesAndRoundtrip() {
        String key = freshKey();

        // 无键
        StepVerifier.create(store.findResponse(key)).verifyComplete();
        // 占位无响应
        assertThat(store.tryAcquire(key, Duration.ofMinutes(1)).block()).isTrue();
        StepVerifier.create(store.findResponse(key)).verifyComplete();
        // 占位上保存首响 → 命中且 status/contentType/body 原样
        StepVerifier.create(store.saveResponse(key, 201, "application/json",
                "{\"task_no\":\"T1\"}")).verifyComplete();
        StepVerifier.create(store.findResponse(key))
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo(201);
                    assertThat(resp.contentType()).isEqualTo("application/json");
                    assertThat(resp.body()).isEqualTo("{\"task_no\":\"T1\"}");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("首响 TTL 沿用占位剩余时长: 保存后 key 仍带正 TTL 且不超占位期")
    void saveResponseKeepsTtl() {
        String key = freshKey();

        assertThat(store.tryAcquire(key, Duration.ofMinutes(2)).block()).isTrue();
        StepVerifier.create(store.saveResponse(key, 200, "application/json", "ok"))
                .verifyComplete();
        Duration ttl = template.getExpire(key).block();
        assertThat(ttl).isNotNull();
        assertThat(ttl.toSeconds()).isPositive();
        assertThat(ttl.toSeconds()).isLessThanOrEqualTo(120);
    }

    @Test
    @DisplayName("首响随占位 TTL 一同过期: 过期后 findResponse 空, 可重新占位")
    void responseExpiresWithTtl() {
        String key = freshKey();

        assertThat(store.tryAcquire(key, Duration.ofSeconds(1)).block()).isTrue();
        StepVerifier.create(store.saveResponse(key, 200, "application/json", "ok"))
                .verifyComplete();
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        StepVerifier.create(store.findResponse(key)).verifyComplete();
        StepVerifier.create(store.tryAcquire(key, Duration.ofMinutes(1)))
                .expectNext(true).verifyComplete();
    }

    @Test
    @DisplayName("Redis 不可达 → tryAcquire fail-open true, release/findResponse 吞错, saveResponse 完成")
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
            StepVerifier.create(deadStore.findResponse("idem:x")).verifyComplete();
            StepVerifier.create(deadStore.saveResponse("idem:x", 200, "application/json", "ok"))
                    .verifyComplete();
        } finally {
            dead.destroy();
        }
    }
}
