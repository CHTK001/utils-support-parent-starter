package com.chua.collapse.support;

import com.chua.common.support.concurrent.collapse.CollapseFlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CollapseFlow} 回归测试。
 *
 * @author CH
 * @since 2026/09/04
 */
class CollapseFlowTest {

    /**
     * B3：首次 execute 后设置 executorFactory 应抛 IllegalStateException（合约强制）
     */
    @Test
    void executorFactoryAfterExecuteThrows() throws Throwable {
        CollapseFlow<String, String> flow = CollapseFlow.of("b3-contract", keys -> "ok");
        flow.execute("first");
        assertThrows(IllegalStateException.class,
                () -> flow.executorFactory(new DefaultCollapseExecutorFactory()),
                "首次 execute 后设置 executorFactory 应抛 IllegalStateException");
    }
}
