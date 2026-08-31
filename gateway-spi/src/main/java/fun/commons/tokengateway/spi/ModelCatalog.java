package fun.commons.tokengateway.spi;

import fun.commons.tokengateway.spi.model.ChatModelVO;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 模型目录面 (MODEL_CATALOG, 设计方案 §4.2).
 *
 * <p>返回调用方可用模型目录; 目录为空 = 租户未开通 (正常业务态, 非错误).
 */
public interface ModelCatalog extends CapabilityFacade {

    Mono<List<ChatModelVO>> list();

    @Override
    default Capability capability() {
        return Capability.MODEL_CATALOG;
    }
}
