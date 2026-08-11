package com.chua.common.support.scattergather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScatterGatherFaultTolerance 故障容忍测试。
 *
 * @author CH
 */
class ScatterGatherFaultToleranceTest {

    @Test
    void testMarkFaultyAfterThreshold() {
        ScatterGatherFaultTolerance ft = new ScatterGatherFaultTolerance(3, 1);
        assertFalse(ft.isFaulty("node-a"));

        ft.recordFailure("node-a");
        ft.recordFailure("node-a");
        assertFalse(ft.isFaulty("node-a"));

        ft.recordFailure("node-a");
        assertTrue(ft.isFaulty("node-a"));
        assertEquals(3, ft.getFailureCount("node-a"));
    }

    @Test
    void testRecovery() {
        ScatterGatherFaultTolerance ft = new ScatterGatherFaultTolerance(2, 1);
        ft.recordFailure("node-b");
        ft.recordFailure("node-b");
        assertTrue(ft.isFaulty("node-b"));

        ft.recordSuccess("node-b");
        assertFalse(ft.isFaulty("node-b"));
    }

    @Test
    void testFaultHandlerCallback() {
        final boolean[] marked = {false};
        final boolean[] recovered = {false};
        ScatterGatherFaultTolerance ft = new ScatterGatherFaultTolerance(1, 1, new ScatterGatherFaultHandler() {
            @Override
            public void onNodeMarkedFaulty(String nodeId, int failureCount) {
                marked[0] = true;
            }

            @Override
            public void onNodeRecovered(String nodeId, int successCount) {
                recovered[0] = true;
            }
        });

        ft.recordFailure("node-c");
        assertTrue(marked[0]);
        ft.recordSuccess("node-c");
        assertTrue(recovered[0]);
    }

    @Test
    void testNodeStatusInfo() {
        ScatterGatherFaultTolerance ft = new ScatterGatherFaultTolerance(2, 1);
        ScatterGatherFaultTolerance.NodeStatusInfo info = ft.getNodeStatus("unknown");
        assertFalse(info.isFaulty());
        assertEquals(0, info.getFailureCount());

        ft.recordFailure("node-d");
        info = ft.getNodeStatus("node-d");
        assertEquals(1, info.getFailureCount());
    }

    @Test
    void testClear() {
        ScatterGatherFaultTolerance ft = new ScatterGatherFaultTolerance(1, 1);
        ft.recordFailure("node-e");
        assertTrue(ft.isFaulty("node-e"));
        ft.clear();
        assertFalse(ft.isFaulty("node-e"));
    }
}