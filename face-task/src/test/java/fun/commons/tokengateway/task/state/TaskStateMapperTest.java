package fun.commons.tokengateway.task.state;

import fun.commons.tokengateway.spi.model.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskStateMapper 单测 (《05》§6 状态映射表).
 */
@DisplayName("TaskStateMapper")
class TaskStateMapperTest {

    @Test
    @DisplayName("SUCCESS→SUCCEEDED; FAILED/CANCELLED→FAILED; PENDING/RUNNING 直映")
    void mappingTable() {
        assertThat(TaskStateMapper.map("SUCCESS")).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(TaskStateMapper.map("FAILED")).isEqualTo(TaskStatus.FAILED);
        assertThat(TaskStateMapper.map("CANCELLED")).isEqualTo(TaskStatus.FAILED);
        assertThat(TaskStateMapper.map("PENDING")).isEqualTo(TaskStatus.PENDING);
        assertThat(TaskStateMapper.map("RUNNING")).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    @DisplayName("未知态/null 保守归 PENDING (非终态, 不触发退款)")
    void unknownIsNonTerminal() {
        assertThat(TaskStateMapper.map(null)).isEqualTo(TaskStatus.PENDING);
        assertThat(TaskStateMapper.map("CANCELLING")).isEqualTo(TaskStatus.PENDING);
        assertThat(TaskStateMapper.map("WHATEVER").isTerminal()).isFalse();
    }
}
