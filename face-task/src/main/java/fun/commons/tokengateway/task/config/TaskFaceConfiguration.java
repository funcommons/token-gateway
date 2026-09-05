package fun.commons.tokengateway.task.config;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务面装配配置 (face=task/all 时由 FaceTaskAssembly 扫描生效).
 *
 * <p>token-gateway.* 能力面配置绑定: SPI 配置模型在 gateway-spi (零 Spring 依赖),
 * 绑定 bean 在此声明 (M0 冻结模型 + 运行期绑定分离).
 * EnableScheduling: 超时钟 TimeoutClockJob + 对账 ReconcileJob (《05》§7 兜底).
 *
 * <p>绑定 bean 双侧条件化 (本类与 worker 的 WorkerConfiguration 同款):
 * 独立部署各场景仅一侧在 context; starter 嵌入同进程两侧共存时先注册者生效,
 * 后注册者让位 (同类型同行为绑定, 谁提供无差别) —— 防 BeanDefinitionOverrideException.
 */
@Configuration
@EnableScheduling
public class TaskFaceConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "token-gateway")
    public TokenGatewayProperties tokenGatewayProperties() {
        return new TokenGatewayProperties();
    }
}
