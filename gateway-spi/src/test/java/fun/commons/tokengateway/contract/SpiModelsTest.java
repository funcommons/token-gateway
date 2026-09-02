package fun.commons.tokengateway.contract;

import fun.commons.tokengateway.spi.config.TaskFaceConfig;
import fun.commons.tokengateway.spi.model.ScanResult;
import fun.commons.tokengateway.spi.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPI 冻结契约面模型速测: 状态机终态语义 / 归属判定 / 审核结果工厂 / 超时窗口解析 / 脱敏 toString.
 */
class SpiModelsTest {

    @Test
    void taskStatus_terminality() {
        assertThat(TaskStatus.PENDING.isTerminal()).isFalse();
        assertThat(TaskStatus.RUNNING.isTerminal()).isFalse();
        assertThat(TaskStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(TaskStatus.EXPIRED.isTerminal()).isTrue();
    }

    @Test
    void ownerType_predicates() {
        assertThat(OwnerType.PLATFORM.isPlatform()).isTrue();
        assertThat(OwnerType.PLATFORM.isTenant()).isFalse();
        assertThat(OwnerType.TENANT.isTenant()).isTrue();
        assertThat(OwnerType.TENANT.isPlatform()).isFalse();
    }

    @Test
    void scanResult_passFactoryAndSanitizeAction() {
        ScanResult pass = ScanResult.pass();
        assertThat(pass.action()).isEqualTo(ScanResult.Action.PASS);
        assertThat(pass.sanitizedContent()).isNull();
        assertThat(pass.ruleCodes()).isEmpty();

        ScanResult sanitized = new ScanResult(ScanResult.Action.SANITIZE, "clean", "rule",
                java.util.List.of("R1"));
        assertThat(sanitized.action()).isEqualTo(ScanResult.Action.SANITIZE);
        assertThat(sanitized.ruleCodes()).containsExactly("R1");
        assertThat(ScanResult.Action.valueOf("BLOCK")).isEqualTo(ScanResult.Action.BLOCK);
    }

    @Test
    void tokenContext_toStringMaskedCredential() {
        fun.commons.tokengateway.spi.model.TokenContext ctx =
                new fun.commons.tokengateway.spi.model.TokenContext(
                        "t-1", "u-1", "tok-1", "g-1", true, "sk-…masked");
        String s = ctx.toString();
        assertThat(s).contains("t-1").contains("u-1").contains("tok-1").contains("g-1")
                .contains("active=true").contains("credential=sk-…masked");
    }

    @Test
    void taskFaceConfig_timeoutOfOverrideAndFallback() {
        TaskFaceConfig cfg = new TaskFaceConfig();
        // 无覆盖 → expireScan 默认 24h
        assertThat(cfg.timeoutOf("video")).isEqualTo(Duration.ofHours(24));
        // null taskType → 默认
        assertThat(cfg.timeoutOf(null)).isEqualTo(Duration.ofHours(24));
        // 按 task_type 覆盖
        cfg.setTimeouts(Map.of("video", Duration.ofHours(2), "image", Duration.ofMinutes(30)));
        assertThat(cfg.timeoutOf("video")).isEqualTo(Duration.ofHours(2));
        assertThat(cfg.timeoutOf("image")).isEqualTo(Duration.ofMinutes(30));
        // 覆盖表里没有的类型 → 回退 expireScan
        assertThat(cfg.timeoutOf("audio")).isEqualTo(Duration.ofHours(24));
        // 显式调小 expireScan 后, 未覆盖类型跟随新默认
        cfg.setExpireScan(Duration.ofMinutes(10));
        assertThat(cfg.timeoutOf("audio")).isEqualTo(Duration.ofMinutes(10));
    }
}
