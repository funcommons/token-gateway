package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 渠道路由请求 (从 backend/contracts 复制, 字段对齐主应用 RPC 入参).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributeRequest {
    private String tenantId;
    private String userId;
    private String apiKeyId;
    private String groupId;
    private String model;
    /** 本次请求已失败的 channel ID (请求内轮换排除用, 首次分发为 null) */
    private List<String> excludeChannelIds;

    /**
     * 接入方幂等键 (#12, 可选): face=task 时 = Idempotency-Key (= MMagiX workId),
     * work 域分发端点凭它回读快照价; chat 路径不传, 行为不变.
     */
    private String idempotencyKey;

    /**
     * 生成参数 (size/ratio/resolution, 协议面 openapi 生图的计价依据; work 域分发端点用).
     * chat 路径不传, 行为不变.
     */
    private java.util.Map<String, Object> params;

    /**
     * 调用方客户端 IP (issue #37, 可选): 与 token-validate 的 clientIp 同构 (#27),
     * 由 controller 层经 ClientIpResolver 解析后沿 prepare/create 透传, 供能力面
     * 分发侧 IP 白名单/风控 (拒绝时回 10612, 网关默认白名单透传为 403).
     * null = 旧语义 (字段缺席), 能力面 fail-closed 行为不变.
     */
    private String clientIp;
}
