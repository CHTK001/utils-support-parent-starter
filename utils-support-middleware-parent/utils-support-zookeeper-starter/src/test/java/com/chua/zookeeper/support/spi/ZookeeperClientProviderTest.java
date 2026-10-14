package com.chua.zookeeper.support.spi;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import com.chua.datasource.support.middleware.MiddlewareTestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZooKeeper SPI 装配自测。
 */
class ZookeeperClientProviderTest {

    private List<MiddlewareClientProvider> collect() {
        ServiceProvider<MiddlewareClientProvider> sp = ServiceProvider.of(MiddlewareClientProvider.class);
        List<MiddlewareClientProvider> out = new ArrayList<>();
        for (ServiceDefinition definition : sp.getPriorityServiceDefinitions()) {
            MiddlewareClientProvider impl = (MiddlewareClientProvider)
                    definition.getObj(sp.getServiceAutowire());
            if (impl != null) {
                out.add(impl);
            }
        }
        return out;
    }

    @Test
    @DisplayName("SPI 注册生效且能按 name=zookeeper 取到")
    void discovered() {
        MiddlewareClientProvider zk = collect().stream()
                .filter(p -> "zookeeper".equals(p.name()))
                .findFirst()
                .orElse(null);
        assertNotNull(zk, "未发现 zookeeper 实现");
        assertTrue(zk instanceof ZookeeperClientProvider);
    }

    @Test
    @DisplayName("元数据口径")
    void metadata() {
        MiddlewareClientProvider zk = collect().stream()
                .filter(p -> "zookeeper".equals(p.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("ZooKeeper 配置中心", zk.displayName());
        assertEquals(2181, zk.defaultPort());
        assertNotNull(zk.capability());
        assertTrue(zk.capability().childNodeSupported());
    }

    @Test
    @DisplayName("不支持的查询返回失败结果而不是抛异常")
    void unsupportedQueryReturnsFail() {
        MiddlewareClientProvider zk = collect().stream()
                .filter(p -> "zookeeper".equals(p.name()))
                .findFirst()
                .orElseThrow();
        var r = zk.query(MiddlewareConnectRequest.of("127.0.0.1", 19092, "", "", ""),
                "create /a b", 10);
        assertNotNull(r);
        assertTrue(r.rows().isEmpty());
        assertFalse(r.message().isBlank(), "拒绝原因不能为空");
    }

    @Test
    @DisplayName("不可达地址的连接测试返回失败对象（不抛异常）")
    void unreachableTestReturnsFail() {
        MiddlewareClientProvider zk = collect().stream()
                .filter(p -> "zookeeper".equals(p.name()))
                .findFirst()
                .orElseThrow();
        MiddlewareTestResult r = zk.test(MiddlewareConnectRequest.of("127.0.0.1", 19092, "", "", ""));
        assertNotNull(r);
        assertFalse(r.connected());
        assertFalse(r.message().isBlank());
    }
}
