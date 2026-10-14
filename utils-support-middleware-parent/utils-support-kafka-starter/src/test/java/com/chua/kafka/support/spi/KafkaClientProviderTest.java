package com.chua.kafka.support.spi;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.datasource.support.middleware.MiddlewareCapability;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kafka SPI 装配自测：验证实现能被 {@link ServiceProvider} 按名称找到。
 *
 * <p>这是整套「宿主不依赖具体中间件」架构的关键验证点 ——
 * 如果这里失败，宿主侧永远拿不到实现，功能必然「未开放」。</p>
 */
class KafkaClientProviderTest {

    /** 把 SPI 全部实现按 name() 收齐，模拟宿主 MiddlewareClientRegistry 的行为 */
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
    @DisplayName("SPI 注册文件能被 ServiceProvider 发现")
    void discovered() {
        List<MiddlewareClientProvider> all = collect();
        assertFalse(all.isEmpty(), "META-INF/extensions 未生效：一个实现都没发现");
    }

    @Test
    @DisplayName("按 name()=kafka 能取到本实现")
    void foundByName() {
        MiddlewareClientProvider kafka = collect().stream()
                .filter(p -> "kafka".equals(p.name()))
                .findFirst()
                .orElse(null);
        assertNotNull(kafka, "未找到 name=kafka 的实现");
        assertTrue(kafka instanceof KafkaClientProvider, "取到的不是 KafkaClientProvider");
    }

    @Test
    @DisplayName("元数据口径：展示名、默认端口、能力声明")
    void metadata() {
        MiddlewareClientProvider kafka = collect().stream()
                .filter(p -> "kafka".equals(p.name()))
                .findFirst()
                .orElseThrow();
        assertEquals("Apache Kafka", kafka.displayName());
        assertEquals(9092, kafka.defaultPort());

        MiddlewareCapability cap = kafka.capability();
        assertNotNull(cap);
        assertTrue(cap.topNodeSupported(), "Kafka 应支持列出主题");
        assertTrue(cap.childNodeSupported(), "Kafka 应支持展开分区");
        assertTrue(cap.querySupported(), "Kafka 应支持只读查询");
        assertTrue(cap.documentSupported(), "Kafka 应支持详情查看");
        assertNotNull(cap.queryHint());
        assertFalse(cap.queryHint().isBlank(), "查询提示不能为空");
    }

    @Test
    @DisplayName("空查询表达式返回可读失败而不是抛异常")
    void emptyQueryFailsGracefully() {
        MiddlewareClientProvider kafka = collect().stream()
                .filter(p -> "kafka".equals(p.name()))
                .findFirst()
                .orElseThrow();
        // 连不上的地址：应返回「失败」结果对象，而不是把异常抛给宿主
        var result = kafka.query(
                com.chua.datasource.support.middleware.MiddlewareConnectRequest.of("127.0.0.1", 19091, "", "", ""),
                "", 10);
        assertNotNull(result);
        // 失败形态：无行 + 有失败原因（两者都要，缺一个都是契约破坏）
        assertTrue(result.rows().isEmpty(), "失败时不应有行数据");
        assertFalse(result.message().isBlank(), "失败原因不能为空");
    }

    @Test
    @DisplayName("不可达地址的连接测试返回失败对象（不抛异常）")
    void unreachableTestReturnsFail() {
        MiddlewareClientProvider kafka = collect().stream()
                .filter(p -> "kafka".equals(p.name()))
                .findFirst()
                .orElseThrow();
        var result = kafka.test(
                com.chua.datasource.support.middleware.MiddlewareConnectRequest.of("127.0.0.1", 19091, "", "", ""));
        assertNotNull(result);
        assertFalse(result.connected(), "不可达地址不应报告连通");
        assertFalse(result.message().isBlank(), "失败原因不能为空");
    }
}
