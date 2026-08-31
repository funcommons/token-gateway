package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.config.HealthReportProperties;

/**
 * 测试辅助: 关闭态 ChannelHealthReporter (不发任何 HTTP, 避免占用 MockWebServer 应答队列).
 */
public final class TestChannelHealthReporters {

    private TestChannelHealthReporters() {
    }

    public static ChannelHealthReporter disabled() {
        HealthReportProperties props = new HealthReportProperties();
        props.setEnabled(false);
        return new ChannelHealthReporter(null, props);
    }
}
