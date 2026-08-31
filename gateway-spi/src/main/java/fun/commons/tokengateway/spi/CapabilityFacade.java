package fun.commons.tokengateway.spi;

/**
 * 能力面接口标记 (M0 冻结契约, 设计方案 §4.2).
 *
 * <p>所有能力面接口的公共父接口; 每个接口对应一个 {@link Capability},
 * 由实现它的 {@link BackendAdapter#capabilities()} 声明.
 */
public interface CapabilityFacade {

    /** 本接口对应的能力面 (启动期校验用). */
    Capability capability();
}
