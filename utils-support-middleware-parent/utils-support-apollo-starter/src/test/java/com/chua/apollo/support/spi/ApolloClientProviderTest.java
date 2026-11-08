package com.chua.apollo.support.spi;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Apollo 配置中心 SPI 管理面装配自测。
 *
 * <p>只验证 SPI 注册是否生效（按 name 取到）、不可达地址的连接测试不抛异常、
 * 不支持的查询返回失败结果，不碰真实的 Apollo 服务。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class ApolloClientProviderTest {

    private MiddlewareClientProvider provider() {
        ServiceProvider<MiddlewareClientProvider> sp = ServiceProvider.of(MiddlewareClientProvider.class);
        for (ServiceDefinition d : sp.getPriorityServiceDefinitions()) {
            MiddlewareClientProvider impl = (MiddlewareClientProvider) d.getObj(sp.getServiceAutowire());
            if (impl != null && "apollo".equals(impl.name())) {
                return impl;
            }
        }
        return null;
    }

    @Test
    @DisplayName("SPI 注册生效且能按 name=apollo 取到")
    void discovered() {
        MiddlewareClientProvider p = provider();
        assertNotNull(p, "未发现 apollo 实现");
        assertEquals("apollo", p.name());
        assertEquals("Apollo 配置中心", p.displayName());
        assertEquals(8080, p.defaultPort());
        assertNotNull(p.capability());
    }

    @Test
    @DisplayName("不可达地址的连接测试返回失败对象（不抛异常）")
    void unreachableTestReturnsFail() {
        MiddlewareClientProvider p = provider();
        assertNotNull(p);
        var r = p.test(MiddlewareConnectRequest.of("127.0.0.1", 19099, "", "", ""));
        assertNotNull(r);
        assertFalse(r.connected());
        assertFalse(r.message().isBlank());
    }

    @Test
    @DisplayName("不支持的写查询返回失败结果而不是抛异常")
    void writeQueryRejected() {
        MiddlewareClientProvider p = provider();
        assertNotNull(p);
        var r = p.query(MiddlewareConnectRequest.of("127.0.0.1", 19099, "", "", ""), "flushall", 10);
        assertNotNull(r);
        assertTrue(r.rows().isEmpty());
        assertFalse(r.message().isBlank());
    }
}
