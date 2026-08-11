package com.chua.common.support.scattergather;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TransportFallbackStrategy 降级策略测试。
 *
 * @author CH
 */
class TransportFallbackStrategyTest {

    @Test
    void testFallbackReturnsSuccess() throws Exception {
        TransportFallbackStrategy strategy = (context, node, timeout) ->
                ScatterGatherResult.success(node.getNodeId(), "fallback-data");

        ScatterGatherContext ctx = new ScatterGatherContext("req-1", "/test", 1000, 1, null);
        ScatterGatherNode node = new ScatterGatherNode("n1", "10.0.0.1", 19001, "tcp", null, Map.of());
        ScatterGatherResult<Object> result = strategy.fallbackInvoke(ctx, node, 1000);
        assertTrue(result.isSuccess());
        assertEquals("fallback-data", result.getData());
    }

    @Test
    void testFallbackReturnsFailure() throws Exception {
        TransportFallbackStrategy strategy = (context, node, timeout) ->
                ScatterGatherResult.failure(node.getNodeId(), "fallback-failed");

        ScatterGatherContext ctx = new ScatterGatherContext("req-2", "/test", 1000, 1, null);
        ScatterGatherNode node = new ScatterGatherNode("n2", "10.0.0.2", 19001, "tcp", null, Map.of());
        ScatterGatherResult<Object> result = strategy.fallbackInvoke(ctx, node, 1000);
        assertFalse(result.isSuccess());
        assertEquals("fallback-failed", result.getErrorMessage());
    }

    @Test
    void testFallbackWithException() {
        TransportFallbackStrategy strategy = (context, node, timeout) -> {
            throw new RuntimeException("fallback error");
        };

        ScatterGatherContext ctx = new ScatterGatherContext("req-3", "/test", 1000, 1, null);
        ScatterGatherNode node = new ScatterGatherNode("n3", "10.0.0.3", 19001, "tcp", null, Map.of());
        assertThrows(RuntimeException.class, () -> {
            try {
                strategy.fallbackInvoke(ctx, node, 1000);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}