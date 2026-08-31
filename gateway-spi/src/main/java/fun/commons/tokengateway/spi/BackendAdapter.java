package fun.commons.tokengateway.spi;

import java.util.Set;

/**
 * 后端适配器总入口 (M0 冻结契约, 设计方案 §4.1).
 *
 * <p>适配器是全局单选 (一次部署一个协议形状); 地址/鉴权/开关全部来自能力面配置
 * (token-gateway.* yml), 同一部署内不混装多后端.
 *
 * <p>SPI 铁律 (设计方案 §4.3):
 * <ol>
 *   <li>适配器无状态: 所有状态 (缓存/连接池) 自管, 配置重载时随适配器重建</li>
 *   <li>错误语义统一: 信封错误码 (10202/10004/10617 等), 不得吞错改语义</li>
 *   <li>超时预算内聚: 每面 yml 独立超时, 实现不得自带超时覆盖配置</li>
 *   <li>凭证不落日志: 承载凭证的对象 toString 必须脱敏</li>
 * </ol>
 */
public interface BackendAdapter {

    /** 后端配置键 (yml 能力面寻址用), 如 mmagix / tokenhub / tokengo / openapi / custom:<spiName>. */
    String backendId();

    /** 能力声明: 适配器自述支持哪些能力面, 启动期校验开关与能力的交集 (fail-fast). */
    Set<Capability> capabilities();
}
