package fun.commons.tokengateway.worker.config;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.lotask.LotaskAuthSigner;
import fun.commons.tokengateway.task.lotask.LotaskTokenStore;
import fun.commons.tokengateway.task.lotask.RouteSnapshotCipher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker 装配: 配置绑定 + 复用 face-task 的 lotask 鉴权 (登录缓存共享 token + HMAC)
 * 与路由快照解密.
 *
 * <p>绑定 bean 条件化 (@ConditionalOnMissingBean): 与 TaskFaceConfiguration 同款绑定,
 * 独立 worker 进程仅本类在 context; starter 嵌入同进程两侧共存时先注册者生效.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
@Import({LotaskAuthSigner.class, LotaskTokenStore.class, RouteSnapshotCipher.class})
public class WorkerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "token-gateway")
    public TokenGatewayProperties tokenGatewayProperties() {
        return new TokenGatewayProperties();
    }
}
