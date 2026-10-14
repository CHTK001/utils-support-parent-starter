package com.chua.influxdb.support.spi;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InfluxDB SPI 装配自测（v2 与 v3 两条口径）。
 */
class InfluxdbClientProviderTest {

    private MiddlewareClientProvider provider() {
        ServiceProvider<MiddlewareClientProvider> sp = ServiceProvider.of(MiddlewareClientProvider.class);
        for (ServiceDefinition d : sp.getPriorityServiceDefinitions()) {
            MiddlewareClientProvider impl = (MiddlewareClientProvider) d.getObj(sp.getServiceAutowire());
            if (impl != null && "influxdb".equals(impl.name())) {
                return impl;
            }
        }
        return null;
    }

    @Test
    @DisplayName("SPI 注册生效且元数据正确")
    void discovered() {
        MiddlewareClientProvider p = provider();
        assertNotNull(p, "未发现 influxdb 实现");
        assertEquals("influxdb", p.name());
        assertEquals("InfluxDB 时序数据库", p.displayName());
        assertEquals(8086, p.defaultPort());
        assertNotNull(p.capability());
        assertTrue(p.capability().querySupported());
    }

    @Test
    @DisplayName("不可达地址的连接测试返回失败对象（不抛异常）")
    void unreachableTestReturnsFail() {
        MiddlewareClientProvider p = provider();
        assertNotNull(p);
        var r = p.test(MiddlewareConnectRequest.of("127.0.0.1", 19096, "", "", ""));
        assertNotNull(r);
        assertFalse(r.connected());
        assertFalse(r.message().isBlank());
    }

    @Test
    @DisplayName("写语句被拒（只读白名单）")
    void writeRejected() {
        MiddlewareClientProvider p = provider();
        assertNotNull(p);
        var r = p.query(MiddlewareConnectRequest.of("127.0.0.1", 19096, "", "", ""),
                "drop database x", 10);
        assertNotNull(r);
        assertTrue(r.rows().isEmpty());
        assertFalse(r.message().isBlank());
    }

    @Test
    @DisplayName("空查询表达式给出可读失败")
    void emptyQueryFails() {
        MiddlewareClientProvider p = provider();
        assertNotNull(p);
        var r = p.query(MiddlewareConnectRequest.of("127.0.0.1", 19096, "", "", ""), "", 10);
        assertNotNull(r);
        assertTrue(r.rows().isEmpty());
        assertFalse(r.message().isBlank());
    }
}
