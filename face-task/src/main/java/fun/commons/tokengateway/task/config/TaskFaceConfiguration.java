package fun.commons.tokengateway.task.config;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 任务面装配配置 (face=task/all 时由 FaceTaskAssembly 扫描生效).
 *
 * <p>token-gateway.* 能力面配置绑定: SPI 配置模型在 gateway-spi (零 Spring 依赖),
 * 绑定 bean 在此声明 (M0 冻结模型 + 运行期绑定分离).
 */
@Configuration
public class TaskFaceConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "token-gateway")
    public TokenGatewayProperties tokenGatewayProperties() {
        return new TokenGatewayProperties();
    }
}
