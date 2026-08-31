package fun.commons.tokengateway.spi;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.spi.model.TokenContext;
import reactor.core.publisher.Mono;

/**
 * 路由解析面 (ROUTE_RESOLVE, 设计方案 §4.2).
 *
 * <p>等价旧 distribute: 返回上游 baseUrl + 出站凭证 + modelMapping
 * (THMP 候选解析同构); 无可用渠道返回 10400 语义.
 */
public interface RouteResolver extends CapabilityFacade {

    /**
     * 解析模型路由.
     *
     * @param model     调用方请求的模型名 (路由绑定通配匹配的输入)
     * @param ctx       已校验的凭证上下文
     * @param requestId 请求 ID (灰度分桶/链路追踪用)
     */
    Mono<DistributeVO> resolve(String model, TokenContext ctx, String requestId);

    @Override
    default Capability capability() {
        return Capability.ROUTE_RESOLVE;
    }
}
