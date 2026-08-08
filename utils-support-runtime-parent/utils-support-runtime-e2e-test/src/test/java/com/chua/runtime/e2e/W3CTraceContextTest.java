package com.chua.runtime.e2e;

import com.chua.runtime.protocol.TraceContextPropagator;
import com.chua.runtime.protocol.W3CTraceContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W3C Trace Context 单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class W3CTraceContextTest {

    @Test
    void injectWithoutContextReturnsValidTraceparent() {
        String header = W3CTraceContext.inject();
        assertNotNull(header);
        W3CTraceContext ctx = W3CTraceContext.extract(header);
        assertNotNull(ctx);
        assertEquals("00", ctx.getVersion());
        assertEquals(32, ctx.getTraceId().length());
        assertEquals(16, ctx.getSpanId().length());
        assertTrue(ctx.isSampled());
    }

    @Test
    void extractValidTraceparent() {
        String header = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        W3CTraceContext ctx = W3CTraceContext.extract(header);
        assertNotNull(ctx);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.getTraceId());
        assertEquals("00f067aa0ba902b7", ctx.getSpanId());
        assertTrue(ctx.isSampled());
    }

    @Test
    void extractInvalidTraceparentReturnsNull() {
        assertNull(W3CTraceContext.extract(null));
        assertNull(W3CTraceContext.extract(""));
        assertNull(W3CTraceContext.extract("garbage"));
        assertNull(W3CTraceContext.extract("00-00000000000000000000000000000000-00f067aa0ba902b7-01"));
        assertNull(W3CTraceContext.extract("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"));
        assertNull(W3CTraceContext.extract("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7"));
    }

    @Test
    void extractWithLeadingWhitespaceAndComma() {
        String header = " 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 ,other=stuff";
        W3CTraceContext ctx = W3CTraceContext.extract(header);
        assertNotNull(ctx);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.getTraceId());
    }

    @Test
    void propagatorInjectCreatesHeader() {
        Map<String, String> headers = new HashMap<>();
        TraceContextPropagator.inject(headers);
        assertEquals(1, headers.size());
        assertTrue(headers.containsKey(W3CTraceContext.HEADER_TRACEPARENT));
        assertNotNull(headers.get(W3CTraceContext.HEADER_TRACEPARENT));
    }

    @Test
    void propagatorExtractCaseInsensitive() {
        Map<String, String> headers = new HashMap<>();
        headers.put("TraceParent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        W3CTraceContext ctx = TraceContextPropagator.peek(headers);
        assertNotNull(ctx);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.getTraceId());
    }

    @Test
    void propagatorExtractMissingReturnsFalse() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        assertFalse(TraceContextPropagator.extract(headers));
        assertNull(TraceContextPropagator.peek(headers));
    }

    @Test
    void propagatorNewOutgoingHeadersIsValid() {
        Map<String, String> headers = TraceContextPropagator.newOutgoingHeaders();
        assertNotNull(headers);
        assertTrue(headers.containsKey(W3CTraceContext.HEADER_TRACEPARENT));
    }

    @Test
    void propagatorInjectIgnoreCaseOverwritesExisting() {
        Map<String, String> headers = new HashMap<>();
        headers.put("TRACEPARENT", "old-value");
        TraceContextPropagator.injectIgnoreCase(headers);
        // 应当覆盖原值
        Object v = headers.get("TRACEPARENT");
        assertNotEquals("old-value", v);
    }

    @Test
    void generateIdsAreUnique() {
        String id1 = W3CTraceContext.generateTraceId();
        String id2 = W3CTraceContext.generateTraceId();
        assertNotEquals(id1, id2);
        assertEquals(32, id1.length());

        String span1 = W3CTraceContext.generateSpanId();
        String span2 = W3CTraceContext.generateSpanId();
        assertNotEquals(span1, span2);
        assertEquals(16, span1.length());
    }

    @Test
    void flagsBitParsing() {
        W3CTraceContext ctx = W3CTraceContext.extract(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");
        assertNotNull(ctx);
        assertFalse(ctx.isSampled());

        W3CTraceContext ctx2 = W3CTraceContext.extract(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertTrue(ctx2.isSampled());
    }
}