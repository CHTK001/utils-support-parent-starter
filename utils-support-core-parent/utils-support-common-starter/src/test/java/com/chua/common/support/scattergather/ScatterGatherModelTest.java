package com.chua.common.support.scattergather;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScatterGather 基础数据模型测试。
 *
 * @author CH
 */
class ScatterGatherModelTest {

    @Test
    void testContext() {
        ScatterGatherContext ctx = new ScatterGatherContext("req-1", "/test", 3000, 2, Map.of("k", "v"));
        assertEquals("req-1", ctx.getRequestId());
        assertEquals("/test", ctx.getPath());
        assertEquals(3000L, ctx.getTimeoutMillis());
        assertEquals(2, ctx.getMinSuccessCount());
        assertEquals("v", ctx.attribute("k"));
        assertFalse(ctx.isWorkerOnly());
    }

    @Test
    void testContextWorkerOnly() {
        ScatterGatherContext ctx = new ScatterGatherContext("req-2", "/test", 1000, 1, null, true);
        ScatterGatherContext worker = ctx.workerOnly();
        assertEquals("req-2", worker.getRequestId());
        assertTrue(worker.isWorkerOnly());
    }

    @Test
    void testNode() {
        ScatterGatherNode node = new ScatterGatherNode("n1", "192.168.1.1", 19001, "tcp", "/svc", Map.of("region", "cn"));
        assertEquals("n1", node.getNodeId());
        assertEquals("192.168.1.1", node.getHost());
        assertEquals(19001, node.getPort());
        assertEquals("tcp", node.getProtocol());
        assertEquals("/svc", node.getServicePath());
        assertEquals("tcp://192.168.1.1:19001", node.getEndpoint());
        assertTrue(node.isTcp());
    }

    @Test
    void testNodeDefaultProtocol() {
        ScatterGatherNode node = new ScatterGatherNode("n2", "10.0.0.1", 19002, null, null, null);
        assertEquals("tcp", node.getProtocol());
        assertTrue(node.isTcp());
    }

    @Test
    void testResultSuccess() {
        ScatterGatherResult<String> r = ScatterGatherResult.success("n1", "data");
        assertTrue(r.isSuccess());
        assertFalse(r.isTimeout());
        assertFalse(r.isFallback());
        assertEquals("data", r.getData());
        assertEquals("n1", r.getNodeId());
    }

    @Test
    void testResultFailure() {
        ScatterGatherResult<String> r = ScatterGatherResult.failure("n1", "error");
        assertFalse(r.isSuccess());
        assertEquals("error", r.getErrorMessage());
    }

    @Test
    void testResultTimeout() {
        ScatterGatherResult<String> r = ScatterGatherResult.timeout("n1", "timeout");
        assertFalse(r.isSuccess());
        assertTrue(r.isTimeout());
    }

    @Test
    void testResultFallback() {
        ScatterGatherResult<String> r = ScatterGatherResult.fallback("n1", "fallback");
        assertTrue(r.isSuccess());
        assertTrue(r.isFallback());
        assertEquals("fallback", r.getData());
    }

    @Test
    void testResultWithRequestId() {
        ScatterGatherResult<Object> result = ScatterGatherResult.success("n1", "test");
        ScatterGatherResultWithRequestId wrapper = new ScatterGatherResultWithRequestId("req-1", result);
        assertEquals("req-1", wrapper.requestId());
        assertEquals("test", wrapper.result().getData());
    }

    @Test
    void testNodeRequest() {
        ScatterGatherNodeRequest req = new ScatterGatherNodeRequest("req-1", "/path", 5000, 1, Map.of("a", "b"));
        assertEquals("req-1", req.getRequestId());
        assertEquals("/path", req.getPath());

        ScatterGatherContext ctx = req.toContext();
        assertEquals("req-1", ctx.getRequestId());
        assertTrue(ctx.isWorkerOnly());

        ScatterGatherNodeRequest fromCtx = ScatterGatherNodeRequest.from(ctx);
        assertEquals("req-1", fromCtx.getRequestId());
    }

    @Test
    void testNodeResponse() {
        ScatterGatherNodeResponse<String> success = ScatterGatherNodeResponse.success("n1", "ok");
        assertTrue(success.isSuccess());
        assertEquals("ok", success.getData());
        ScatterGatherResult<String> r = success.toResult();
        assertTrue(r.isSuccess());
        assertEquals("ok", r.getData());

        ScatterGatherNodeResponse<String> failure = ScatterGatherNodeResponse.failure("n1", "fail");
        assertFalse(failure.isSuccess());
        assertEquals("fail", failure.getErrorMessage());
    }

    @Test
    void testDefaultAggregator() {
        DefaultScatterGatherAggregator<String> agg = new DefaultScatterGatherAggregator<>();
        ScatterGatherContext ctx = new ScatterGatherContext("req-1", "/test", 1000, 1, null);
        String result = agg.aggregate(ctx, java.util.List.of(
                ScatterGatherResult.failure("n1", "err"),
                ScatterGatherResult.success("n2", "data")
        ));
        assertEquals("data", result);
    }
}