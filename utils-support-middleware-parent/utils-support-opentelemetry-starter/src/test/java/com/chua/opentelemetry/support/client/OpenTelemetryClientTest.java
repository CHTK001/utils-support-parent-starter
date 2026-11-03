// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.opentelemetry.support.client;

import com.chua.runtime.protocol.Span;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.Trace;
import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenTelemetry 客户端测试
 * <p>
 * 使用 {@link io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter} 原地缓存 Span,
 * 不依赖任何外部 OTLP 接收机。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class OpenTelemetryClientTest {

    /**
     * 无 Span 时查询返回空列表
     */
    @Test
    void tracesEmptyWhenNoSpan() {
        try (OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("test").build()) {
            assertTrue(client.traces().isEmpty());
            assertTrue(client.spans().isEmpty());
            assertNull(client.trace("trace"));
        }
    }

    /**
     * 单个根 Span 可创建、可结束、可查询
     */
    @Test
    void createAndQuerySpan() {
        try (OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("test").build()) {
            io.opentelemetry.api.trace.Span span = client.startSpan("root");
            assertTrue(span.isRecording());
            span.setAttribute("class", "com.example.Demo");
            span.setAttribute("method", "com.example.Demo#hello");
            span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
            span.end();
            List<Span> list = client.spans();
            assertEquals(1, list.size());
            Span one = list.get(0);
            assertEquals("root", one.getName());
            assertEquals("com.example.Demo", one.getClassName());
            assertEquals("com.example.Demo#hello", one.getMethodName());
            assertEquals(StatusCode.OK, one.getStatus());
            assertNull(one.getMessage());
            assertTrue(one.getStartTime() > 0);
            assertTrue(one.getEndTime() > one.getStartTime());
            assertTrue(one.getEvents().isEmpty());
        }
    }

    /**
     * 父子 Span 能拼装成一条 Trace, 并且有起点与终点
     */
    @Test
    void childSpanBuildTrace() {
        try (OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("test").build()) {
            io.opentelemetry.api.trace.Span root = client.startSpan("root");
            io.opentelemetry.api.trace.Span child = client.tracer()
                    .spanBuilder("child")
                    .setParent(root)
                    .setSpanKind(SpanKind.CLIENT)
                    .startSpan();
            child.setAttribute("error.type", "java.io.IOException");
            child.setAttribute("error.message", "connection refused");
            child.end();
            root.end();
            List<Trace> traces = client.traces();
            assertEquals(1, traces.size());
            Trace trace = traces.get(0);
            assertEquals(root.getSpanContext().getTraceId(), trace.getTraceId());
            assertEquals("root", trace.getRoot().getName());
            assertEquals(2, trace.getSpans().size());
            // 按开始时间排序: 父 -> 子
            assertEquals("root", trace.getSpans().get(0).getName());
            assertEquals("child", trace.getSpans().get(1).getName());
            // error.type / error.message 被转写为协议的异常标识
            assertEquals("java.io.IOException", trace.getSpans().get(1).getErrorType());
            assertEquals("connection refused", trace.getSpans().get(1).getErrorMessage());
            List<Span> list = client.spans();
            List<Span> rootList = client.spans("root");
            assertEquals(1, rootList.size());
            assertEquals(root.getSpanContext().getTraceId(), rootList.get(0).getTraceId());
            assertEquals(root.getSpanContext().getTraceId(), list.get(0).getTraceId());
            assertEquals(2, list.size());
        }
    }

    /**
     * 错误状态与 Trace 过滤
     */
    @Test
    void errorStatusAndTraceFilter() {
        try (OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("test").build()) {
            io.opentelemetry.api.trace.Span span = client.startSpan("fail");
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            span.end();
            List<Span> list = client.spans();
            assertEquals(1, list.size());
            assertEquals(StatusCode.ERROR, list.get(0).getStatus());
            List<Span> byTrace = client.spansByTraceId(list.get(0).getTraceId());
            assertEquals(1, byTrace.size());
            List<Span> byName = client.spans("no-such-name");
            assertTrue(byName.isEmpty());
        }
    }

    /**
     * 关闭后的查询返回空列表, 创建 Span/获取 Tracer 抛出 IllegalStateException
     */
    @Test
    void closedClientDeniesOperation() {
        OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("test").build();
        client.startSpan("before").end();
        client.close();
        assertTrue(client.isClosed());
        assertTrue(client.traces().isEmpty());
        assertTrue(client.spans().isEmpty());
        assertNull(client.trace("trace"));
        assertThrows(IllegalStateException.class, () -> client.startSpan("after"));
        assertThrows(IllegalStateException.class, () -> client.startSpan("after", SpanKind.SERVER));
        assertThrows(IllegalStateException.class, () -> client.tracer());
        client.close();
    }

    /**
     * 关闭方法幂等
     */
    @Test
    void closeIsIdempotent() {
        OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("test").build();
        client.close();
        client.close();
        assertTrue(client.isClosed());
    }

    /**
     * 空服务名不应导致 NPE
     */
    @Test
    void defaultServiceName() {
        try (OpenTelemetryClient client = OpenTelemetryClient.builder().build()) {
            assertEquals("unknown-service", client.getServiceName());
            assertTrue(client.traces().isEmpty());
        }
    }
}
