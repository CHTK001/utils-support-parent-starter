// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.opentelemetry.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.opentelemetry.support.client.OpenTelemetryClient;
import com.chua.opentelemetry.support.datasource.OpenTelemetryDataSource;
import com.chua.runtime.protocol.Span;
import com.chua.runtime.protocol.Trace;
import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenTelemetry 引擎 SPI 与查询测试
 *
 * @author CH
 * @since 4.0.0.50
 */
class OpenTelemetryEngineTest {

    private OpenTelemetryEngine create() {
        return (OpenTelemetryEngine) Engine.create("opentelemetry");
    }

    /**
     * SPI 配置文件必须注册, 否则 {@link Engine#create(String)} 解析不到引擎
     */
    @Test
    void spiCreate() {
        OpenTelemetryEngine engine = create();
        try {
            assertNotNull(engine);
            assertTrue(engine instanceof OpenTelemetryEngine);
            assertFalse(engine.isClosed());
        } finally {
            engine.close();
        }
    }

    /**
     * 添加客户端数据源, 按客户端的端点记账
     */
    @Test
    void addDataSource() {
        OpenTelemetryEngine engine = create();
        try {
            OpenTelemetryClient clientA = OpenTelemetryClient.builder().serviceName("a").build();
            engine.addDataSource("a", clientA);
            EngineDataSource<OpenTelemetryClient> dataSourceA = engine.getDataSource("a");
            assertEquals(clientA, dataSourceA.getSource());
            assertEquals("a", dataSourceA.name());
            assertEquals(clientA.getEndpoint(), dataSourceA.url());
            assertEquals(clientA, dataSourceA.getSource());
            assertEquals(1, engine.dataSourceNames().size());
            assertTrue(engine.hasDataSource("a"));
            assertNull(engine.getDataSource("b"));
            assertEquals(clientA, engine.client("a"));
            assertEquals(clientA, engine.getDataSource().getSource());
        } finally {
            engine.close();
        }
    }

    /**
     * 添加地址字符串格式的数据源
     */
    @Test
    void addDataSourceByUrl() {
        OpenTelemetryEngine engine = create();
        try {
            engine.addDataSource("http", "http://localhost:4318");
            assertEquals(1, engine.dataSourceNames().size());
            OpenTelemetryClient client = engine.client("http");
            assertEquals("http", client.getServiceName());
            assertEquals("http://localhost:4318", client.getEndpoint());
        } finally {
            engine.close();
        }
    }

    /**
     * 没有名称时拒绝
     */
    @Test
    void addDataSourceWithoutName() {
        OpenTelemetryEngine engine = create();
        try {
            OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("test").build();
            assertThrows(IllegalArgumentException.class, () -> engine.addDataSource(null, (EngineDataSource<Object>) null));
            assertThrows(IllegalArgumentException.class, () -> engine.addDataSource("", client));
        } finally {
            engine.close();
        }
    }

    /**
     * 开关后不可再注册数据源
     */
    @Test
    void closedEngineDeniesOperation() {
        OpenTelemetryEngine engine = create();
        try {
            OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("test").build();
            engine.addDataSource("test", client);
            engine.close();
            assertTrue(engine.isClosed());
            assertEquals(0, engine.dataSourceNames().size());
            assertThrows(IllegalStateException.class, () -> engine.client("test"));
            assertThrows(IllegalStateException.class, () -> engine.client());
            assertThrows(IllegalStateException.class, () -> engine.addDataSource("test", client));
        } finally {
            engine.close();
        }
    }

    /**
     * 查询路径: traces() / spans() / trace() / spans(name)
     */
    @Test
    void queryTraces() {
        OpenTelemetryEngine engine = create();
        try {
            engine.addDataSource("default", OpenTelemetryClient.builder().serviceName("default").build());
            OpenTelemetryClient client = engine.client();
            io.opentelemetry.api.trace.Span span = client.startSpan("api");
            span.setAttribute("class", "com.example.Demo");
            span.end();
            List<Trace> traces = engine.traces();
            assertEquals(1, traces.size());
            List<Span> spans = engine.spans();
            assertEquals(1, spans.size());
            assertEquals("api", spans.get(0).getName());
            assertEquals("com.example.Demo", spans.get(0).getClassName());
            assertEquals(0, engine.spans("nope").size());
            assertNull(engine.trace("nope"));
        } finally {
            engine.close();
        }
    }

    /**
     * 查询与默认数据源一致
     */
    @Test
    void queryAfterDefaultDataSource() {
        OpenTelemetryEngine engine = create();
        try {
            engine.addDataSource("default", OpenTelemetryClient.builder().serviceName("default").build());
            io.opentelemetry.api.trace.Span span = engine.client().startSpan("api");
            span.end();
            assertEquals(1, engine.client().spans().size());
            assertEquals(1, engine.traces().size());
            assertEquals(1, engine.spans().size());
            assertEquals(1, engine.traces().get(0).getSpans().size());
            assertEquals("default", engine.client().getServiceName());
        } finally {
            engine.close();
        }
    }

    /**
     * 存储能力为只读
     */
    @Test
    void unsupportedOperations() {
        OpenTelemetryEngine engine = create();
        try {
            assertThrows(UnsupportedOperationException.class, () -> engine.store("t", List.of(1)));
            assertThrows(UnsupportedOperationException.class, () -> engine.query(String.class));
            assertThrows(UnsupportedOperationException.class, () -> engine.update(String.class));
            assertThrows(UnsupportedOperationException.class, () -> engine.delete(String.class));
            assertThrows(UnsupportedOperationException.class, () -> engine.meta());
            assertFalse(engine.supportsSql());
            assertFalse(engine.supportsMeta());
            assertNull(engine.getExecutor());
            assertNull(engine.getDialect("default"));
        } finally {
            engine.close();
        }
    }

    /**
     * 关闭后查询返回空列表
     */
    @Test
    void closedEngineQueryEmpty() {
        OpenTelemetryEngine engine = create();
        OpenTelemetryClient client = OpenTelemetryClient.builder().serviceName("test").build();
        engine.addDataSource("test", client);
        assertEquals(0, engine.traces().size());
        engine.close();
        assertTrue(engine.isClosed());
        assertEquals(0, engine.dataSourceNames().size());
        assertThrows(IllegalStateException.class, () -> engine.traces());
        assertThrows(IllegalStateException.class, () -> engine.spans());
    }

    /**
     * 两次 SPI 创建返回不同的实例, 关闭互相独立
     */
    @Test
    void spiCreateSeparateInstances() {
        OpenTelemetryEngine a = create();
        OpenTelemetryEngine b = create();
        try {
            assertFalse(a.isClosed());
            a.close();
            assertTrue(a.isClosed());
            assertFalse(b.isClosed());
        } finally {
            a.close();
            b.close();
        }
    }
}
