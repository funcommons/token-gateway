package fun.commons.tokengateway.spi;

import fun.commons.tokengateway.spi.model.ModerationRequest;
import fun.commons.tokengateway.spi.model.ScanResult;
import reactor.core.publisher.Mono;

/**
 * 内容审核面 (MODERATION_SCAN, 设计方案 §4.2).
 *
 * <p>fail-open/fail-close 策略由 yml 配置注入 (对齐 moderation-fail-open-behavior),
 * 实现方不得自创口径; 超时/5xx 由管线按注入策略决定放行或拦截, 不重试拖慢主链路.
 */
public interface ModerationScanner extends CapabilityFacade {

    Mono<ScanResult> scan(ModerationRequest request);

    @Override
    default Capability capability() {
        return Capability.MODERATION_SCAN;
    }
}
