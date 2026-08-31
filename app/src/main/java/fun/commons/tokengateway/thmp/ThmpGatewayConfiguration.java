package fun.commons.tokengateway.thmp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * THMP 影子双跑装配 (gateway.thmp.enabled 总开关).
 *
 * <p>enabled=false (默认): 仅装配 ThmpShadow.Noop + ThmpCutover.Noop, 零远端调用零开销;
 * enabled=true: 契约客户端 + SWR 缓存 + 比对器全链装配.
 */
@Configuration
public class ThmpGatewayConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "gateway.thmp", name = "enabled", havingValue = "true")
    public ThmpContractClient thmpContractClient(
            org.springframework.web.reactive.function.client.WebClient.Builder builder,
            ThmpContractProperties props) {
        return new ThmpContractClient(builder, props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway.thmp", name = "enabled", havingValue = "true")
    public ThmpCandidateCache thmpCandidateCache(ThmpContractClient client, ThmpContractProperties props) {
        return new ThmpCandidateCache(client, props.getCacheTtl(), props.getNegativeTtl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway.thmp", name = "enabled", havingValue = "true")
    public ThmpShadow thmpShadowEnabled(ThmpCandidateCache cache) {
        return new ThmpShadowComparator(cache);
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway.thmp", name = "enabled", havingValue = "true")
    public ThmpKeyCipher thmpKeyCipher(ThmpContractProperties props) {
        return new ThmpKeyCipher(java.util.Map.of(), props.getKeyPassphrase());
    }

    /**
     * 切流入口: enabled 且名单非空才装配真实现, 否则 Noop (旧链路直走).
     * 一键回旧 = 配置清空 cutoverModels (重启生效; 动态化随 W3 运营).
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.thmp", name = "enabled", havingValue = "true")
    public ThmpCutover thmpCutover(ThmpContractProperties props, ThmpCandidateCache cache,
                                   ThmpKeyCipher keyCipher) {
        if (props.getCutoverModels() == null || props.getCutoverModels().isEmpty()) {
            return new ThmpCutover.Noop();
        }
        return new ThmpCutoverRouter(props, cache, keyCipher);
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway.thmp", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    public ThmpShadow thmpShadowNoop() {
        return new ThmpShadow.Noop();
    }

    /**
     * enabled=false (默认) 时的切流兜底: RelayOrchestrator 强依赖 ThmpCutover,
     * 缺 bean 会启动失败; Noop 恒回空 = 旧链路直走.
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.thmp", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    public ThmpCutover thmpCutoverNoop() {
        return new ThmpCutover.Noop();
    }
}
