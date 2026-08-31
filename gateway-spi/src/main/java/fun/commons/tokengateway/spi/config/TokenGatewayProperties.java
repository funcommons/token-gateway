package fun.commons.tokengateway.spi.config;

import lombok.Data;

/**
 * token-gateway 能力面配置根 (token-gateway.*, 设计方案 §5.1, M0 冻结模型).
 *
 * <p>无注册概念: 后端在 yml 中按能力面配置 (url + auth + 开关), 每类服务一个地址,
 * 日志/计费/审核等服务可分离部署, 也可全部指向同一单体 (代码零分支).
 * 变更方式 = 改 yml + 重启 (热更新列 M4).
 */
@Data
public class TokenGatewayProperties {

    /** 装配面 (部署分组): llm | task | all. */
    private Face face = Face.ALL;

    /** 协议形状适配器 (全局单选): mmagix | tokenhub | tokengo | openapi | custom:<spiName>. */
    private String adapter = "mmagix";

    /** 路由/分发服务 (distribute / 候选解析). */
    private RouteFaceConfig route = new RouteFaceConfig();

    /** 凭证校验服务. */
    private EndpointConfig tokenValidate = new EndpointConfig();

    /** 计费服务 (预扣/结算/退款). */
    private BillingFaceConfig billing = new BillingFaceConfig();

    /** 内容审核服务. */
    private ModerationFaceConfig moderation = new ModerationFaceConfig();

    /** 日志服务 (rpc/mq 两类投递通道). */
    private AccessLogFaceConfig accessLog = new AccessLogFaceConfig();

    /** 审计服务 (安全/管理事件, 含审核审计上报); url 为空 = 仅本地日志不外发. */
    private EndpointConfig audit = new EndpointConfig();

    /** 模型目录服务. */
    private EndpointConfig modelCatalog = new EndpointConfig();

    /** 任务面参数 (face=task/all 时生效). */
    private TaskFaceConfig task = new TaskFaceConfig();

    /** 渠道健康信号回传 (record-success/failure); off 时需在监控侧补偿. */
    private boolean healthReport = true;
}
