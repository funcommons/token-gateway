package fun.commons.tokengateway.spi.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 路由/分发面配置 (token-gateway.route, 设计方案 §5.1).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RouteFaceConfig extends EndpointConfig {

    /** 模型绑定: 先命中先得, 支持通配 (如 ["gpt-*", "claude-*", "*"]). */
    private List<ModelBinding> routes = new ArrayList<>();

    /**
     * token-route 路由表标识 (G2/G5: adapter=tokengo|openapi 时 resolve 的 table_id;
     * 部署方在 token-route 表 entry 的 data_json 里配 DistributeVO 同名字段).
     */
    private String tableId;

    /** 后端间灰度: 影子并行解析目标 (比对埋点不执行, 设计方案 §7). */
    private String shadowTo;

    /** 后端间灰度: 确定性分桶切流百分比 0~100 (归零后按分桶切流, 清名单秒级回滚). */
    private int cutoverPercent;

    {
        setTimeout(Duration.ofSeconds(3));
    }

    @Data
    public static class ModelBinding {
        private List<String> models = new ArrayList<>();
    }
}
