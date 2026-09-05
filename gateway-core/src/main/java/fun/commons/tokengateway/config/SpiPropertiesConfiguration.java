package fun.commons.tokengateway.config;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SPI 能力面配置绑定 (token-gateway.*, 设计方案 §5.1) — G1 装配收尾.
 *
 * <p>M1 平移态的绑定 bean 分散在 face-task (TaskFaceConfiguration); G1 统一收口到
 * core: 核心扫描包 (fun.commons.tokengateway.config) 在所有部署形态 (app fat-jar /
 * starter 嵌入) 都被扫描, 独立 worker 进程仍由 WorkerConfiguration 自绑定.
 * 条件化同款 (与 WorkerConfiguration 双侧共存时先注册者生效).
 */
@Configuration
public class SpiPropertiesConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "token-gateway")
    public TokenGatewayProperties tokenGatewayProperties() {
        return new TokenGatewayProperties();
    }
}
