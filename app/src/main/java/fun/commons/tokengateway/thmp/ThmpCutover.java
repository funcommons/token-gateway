package fun.commons.tokengateway.thmp;

import fun.commons.tokengateway.contract.DistributeVO;
import reactor.core.publisher.Mono;

/**
 * THMP 灰度切流入口 (22 号 S2-W3 前置).
 *
 * <p>语义: 命中切流 → Mono 发旧链路同构的 {@link DistributeVO} (THMP 候选转换);
 * 未命中 / THMP 失败 / 密钥不可解 → <b>Mono.empty()</b> = 调用方回旧 distribute (失败回旧,
 * 一键回旧 = 配置清空名单). 实现绝不抛错、绝不阻塞主链.
 */
public interface ThmpCutover {

    /**
     * 是否切流 (确定性判定, 供 route 前置短路; 也可只调 route).
     */
    boolean shouldCut(String model, String tenantId, String requestId);

    /**
     * THMP 候选 → 旧链路路由对象; 空 Mono = 回旧.
     */
    Mono<DistributeVO> route(String model, String tenantId, String requestId);

    /**
     * 关闭态: 恒空 (旧链路直走).
     */
    class Noop implements ThmpCutover {

        @Override
        public boolean shouldCut(String model, String tenantId, String requestId) {
            return false;
        }

        @Override
        public Mono<DistributeVO> route(String model, String tenantId, String requestId) {
            return Mono.empty();
        }
    }
}
