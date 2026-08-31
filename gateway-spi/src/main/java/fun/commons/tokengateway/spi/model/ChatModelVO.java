package fun.commons.tokengateway.spi.model;

/**
 * 模型目录条目 (MODEL_CATALOG 面产物, M0 冻结契约).
 *
 * @param id      模型名 (调用方请求 model 字段取值, 路由绑定通配匹配的输入)
 * @param ownedBy 归属后端标识 (mmagix / tokenhub / …)
 */
public record ChatModelVO(String id, String ownedBy) {
}
