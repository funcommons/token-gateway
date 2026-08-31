package fun.commons.tokengateway.spi;

import fun.commons.tokengateway.spi.model.TokenContext;
import reactor.core.publisher.Mono;

/**
 * 凭证校验面 (TOKEN_VALIDATE, 设计方案 §4.2).
 *
 * <p>高频热点面: 实现方建议本地缓存 (60s TTL) + 禁用即时失效权衡
 * (后端接入开发手册 §4.1).
 */
public interface TokenValidator extends CapabilityFacade {

    /**
     * 校验调用方凭证.
     *
     * @param credential Authorization 凭证原文 (token / sk- key), 明文只进本入参, 不落日志
     * @return 凭证上下文; 无效/过期返回 10202/10200 语义 (业务失败, 非异常)
     */
    Mono<TokenContext> validate(String credential);

    @Override
    default Capability capability() {
        return Capability.TOKEN_VALIDATE;
    }
}
