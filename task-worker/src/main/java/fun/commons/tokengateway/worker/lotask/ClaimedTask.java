package fun.commons.tokengateway.worker.lotask;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Worker 抢占到的任务 (lotask4j worker 域 PollTaskResponse 最小契约).
 *
 * <p>fencing: 上报进度/结果必须回传 executionToken + version (平台 CAS 校验,
 * Worker 切换后老 token 上报被拒); leaseExpireAt 前须 progress 续约或完成上报.
 *
 * <p><b>version 本地递增</b>: 平台 progressWithVersion 成功即 version+1 且不回传新值 ——
 * {@link #bumpVersion()} 由上报客户端在 CAS 成功后同步, 否则第二次 progress 起
 * 全部 fencing 失败, lease 到期任务被平台 reaper 置 FAILED.
 */
public final class ClaimedTask {

    private final String id;
    private final String type;
    private final Map<String, Object> payload;
    private final Long executionToken;
    private final Integer attempt;
    private final OffsetDateTime leaseExpireAt;

    /** 平台侧乐观锁版本 (CAS 成功后由 bumpVersion 同步递增). */
    private volatile int version;

    public ClaimedTask(String id, String type, Map<String, Object> payload,
                       Long executionToken, Integer version, Integer attempt,
                       OffsetDateTime leaseExpireAt) {
        this.id = id;
        this.type = type;
        this.payload = payload;
        this.executionToken = executionToken;
        this.version = version == null ? 0 : version;
        this.attempt = attempt;
        this.leaseExpireAt = leaseExpireAt;
    }

    public String id() { return id; }

    public String type() { return type; }

    public Map<String, Object> payload() { return payload; }

    public Long executionToken() { return executionToken; }

    public int version() { return version; }

    /** fencing CAS 成功后同步平台侧 version+1. */
    public void bumpVersion() { version++; }

    public Integer attempt() { return attempt; }

    public OffsetDateTime leaseExpireAt() { return leaseExpireAt; }
}
