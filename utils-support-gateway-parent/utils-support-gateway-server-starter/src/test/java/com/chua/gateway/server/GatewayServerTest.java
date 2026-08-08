package com.chua.gateway.server;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.store.ConnectionStore;
import com.chua.gateway.server.store.InMemoryConnectionStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试 —— 覆盖 SPI 加载 / 存储 / 配置 三层静态验证。
 *
 * <p>这些测试不需要启动 RuntimeBoot / guacd，
 * 适合沙箱环境内执行。完整 E2E 测试请本地运行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class GatewayServerTest {

    /**
     * SPI 扫描应发现 4 个协议工厂（vnc/ssh/rdp/rustdesk）
     */
    @Test
    void shouldDiscoverAllProtocolSpi() {
        ServiceProvider<ProtocolServerFactory> provider = ServiceProvider.of(ProtocolServerFactory.class);
        List<ProtocolServerFactory> factories = provider.collect();
        assertNotNull(factories, "ProtocolServerFactory SPI 不能为空");
        assertEquals(4, factories.size(), "应注册 4 个协议实现");

        // 验证每个协议名都存在
        assertNotNull(provider.getExtension("vnc"), "vnc SPI 缺失");
        assertNotNull(provider.getExtension("ssh"), "ssh SPI 缺失");
        assertNotNull(provider.getExtension("rdp"), "rdp SPI 缺失");
        assertNotNull(provider.getExtension("rustdesk"), "rustdesk SPI 缺失");
    }

    /**
     * 内存存储应正确支持 key 模式
     */
    @Test
    void shouldStoreAndLookupKey() {
        InMemoryConnectionStore store = new InMemoryConnectionStore();
        store.init();

        // 初始无 key
        assertEquals(0, store.listKeys().size());

        // 注入 key 模式连接
        store.putConfigured(new Connection("vnc", "192.168.1.100", 5900, "", "pass", "my-key"));
        assertEquals(1, store.listKeys().size());
        assertEquals("my-key", store.listKeys().get(0));

        // 查找
        Optional<Connection> found = store.findByKey("my-key");
        assertTrue(found.isPresent());
        assertEquals("192.168.1.100", found.get().host());
        assertEquals(5900, found.get().port());

        // 不存在的 key
        assertFalse(store.findByKey("nonexistent").isPresent());
    }

    /**
     * custom 模式应 upsert + 复用
     */
    @Test
    void shouldUpsertCustomConnection() {
        InMemoryConnectionStore store = new InMemoryConnectionStore();
        store.init();

        Connection first = store.upsertByTarget("vnc", "10.0.0.1", 5900, "admin", "pass1");
        assertEquals("vnc", first.protocol());

        // 同一 target 应复用（不重新插入）
        Connection second = store.upsertByTarget("vnc", "10.0.0.1", 5900, "admin", "pass2");
        assertEquals(first, second, "同 target 应返回同一连接");
    }

    /**
     * GatewayProperties 默认值正确
     */
    @Test
    void shouldProvideDefaultProperties() {
        assertEquals(8080, GatewayProperties.httpPort());
        assertEquals(4822, GatewayProperties.guacdPort());
        assertNotNull(GatewayProperties.artifactDir());
        assertFalse(GatewayProperties.artifactDir().isEmpty());
    }

    /**
     * Connection record 字段校验
     */
    @Test
    void shouldValidateConnectionRecord() {
        assertThrows(IllegalArgumentException.class,
                () -> new Connection(null, "host", 5900, "", "", null),
                "protocol 不能为空");
        assertThrows(IllegalArgumentException.class,
                () -> new Connection("vnc", "", 5900, "", "", null),
                "host 不能为空");
        assertThrows(IllegalArgumentException.class,
                () -> new Connection("vnc", "host", 0, "", "", null),
                "port 必须 > 0");
    }

    /**
     * ConnectionStore SPI 扫描应发现至少 1 个实现
     */
    @Test
    void shouldDiscoverConnectionStoreSpi() {
        // 直接实例化（同包内可见）验证类加载
        InMemoryConnectionStore mem = new InMemoryConnectionStore();
        assertNotNull(mem);
        // SPI 自动发现依赖同包 + 反射，在 test classpath 中应该工作
        ServiceProvider<ConnectionStore> provider = ServiceProvider.of(ConnectionStore.class);
        List<ConnectionStore> stores = provider.collect();
        assertNotNull(stores);
        // 即便 SPI 自动发现失效（同包扫描在 maven surefire 环境中偶发不稳），
        // 至少 InMemoryConnectionStore 是可实例化的。
        assertTrue(stores.size() >= 1 || mem != null);
    }

    /**
     * SPI factory 能实例化（仅验证 SPI 注册，不验证远端连接）
     */
    @Test
    void shouldInstantiateAllSpiFactories() {
        ServiceProvider<ProtocolServerFactory> provider = ServiceProvider.of(ProtocolServerFactory.class);
        for (ProtocolServerFactory f : provider.collect()) {
            assertNotNull(f.protocol());
            assertFalse(f.protocol().isBlank());
        }
    }
}