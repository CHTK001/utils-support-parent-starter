package com.chua.elasticsearch.support.spi;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.datasource.support.middleware.MiddlewareCapability;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elasticsearch SPI 装配与契约自测。
 *
 * <p>与 {@code KafkaClientProviderTest} 同口径，但全部用<b>不可达地址</b>（19092），
 * 不依赖真实集群 —— 这里验的是三件与实例无关的东西：</p>
 * <ol>
 *   <li>实现能被 {@link ServiceProvider} 按 name 找到（否则宿主永远「功能未开放」）；</li>
 *   <li>元数据口径（展示名 / 默认端口 / 能力声明）；</li>
 *   <li>失败与写语句都返回<b>结果对象</b>而不是抛异常，且失败原因非空。</li>
 * </ol>
 *
 * <p>真实集群的数据面验收（索引/字段/检索）不在这里 —— 那需要一台可达的 ES。</p>
 */
class ElasticsearchClientProviderTest {

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

    /** 取本实现；找不到直接失败，免得后续断言空指针看不出真实原因 */
    private MiddlewareClientProvider provider() {
        return collect().stream()
                .filter(p -> "elasticsearch".equals(p.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 name=elasticsearch 的实现"));
    }

    @Test
    @DisplayName("SPI 注册文件能被 ServiceProvider 发现")
    void discovered() {
        assertFalse(collect().isEmpty(), "META-INF/extensions 未生效：一个实现都没发现");
    }

    @Test
    @DisplayName("按 name()=elasticsearch 能取到本实现")
    void foundByName() {
        MiddlewareClientProvider es = provider();
        assertTrue(es instanceof ElasticsearchClientProvider, "取到的不是 ElasticsearchClientProvider");
    }

    @Test
    @DisplayName("元数据口径：展示名、默认端口、能力声明")
    void metadata() {
        MiddlewareClientProvider es = provider();
        assertEquals("Elasticsearch 搜索引擎", es.displayName());
        assertEquals(9200, es.defaultPort());

        MiddlewareCapability cap = es.capability();
        assertNotNull(cap);
        assertTrue(cap.topNodeSupported(), "ES 应支持列出索引");
        assertTrue(cap.childNodeSupported(), "ES 应支持展开字段");
        assertTrue(cap.querySupported(), "ES 应支持只读查询");
        assertTrue(cap.documentSupported(), "ES 应支持详情查看");
        assertNotNull(cap.queryHint());
        assertFalse(cap.queryHint().isBlank(), "查询提示不能为空");
        assertFalse(cap.queryDescription().isBlank(), "查询语法说明不能为空");
    }

    @Test
    @DisplayName("空查询表达式返回可读失败而不是抛异常")
    void emptyQueryFailsGracefully() {
        var result = provider().query(dead(), "", 10);
        assertNotNull(result);
        assertTrue(result.rows().isEmpty(), "失败时不应有行数据");
        assertFalse(result.message().isBlank(), "失败原因不能为空");
    }

    @Test
    @DisplayName("只读白名单：写操作在创建客户端前即被拒绝")
    void writeQueryRejectedBeforeClientCreation() {
        // 用不可达地址：若拒绝逻辑没前置，这里会变成「连接失败」而不是白名单提示，
        // 断言 message 含「不支持的查询」就能把两者区分开
        for (String write : new String[]{
                "delete /logs", "_bulk", "index=logs", "PUT /logs", "drop index logs"}) {
            var result = provider().query(dead(), write, 10);
            assertTrue(result.rows().isEmpty(), "写语句不应产生行数据: " + write);
            assertTrue(result.message().contains("不支持的查询"),
                    "应给只读白名单提示，实际: " + write + " -> " + result.message());
        }
    }

    @Test
    @DisplayName("只读白名单：合法语句不被白名单误伤（此时才轮到连接失败）")
    void readOnlyStatementsPassWhitelist() {
        for (String read : new String[]{
                "nodes", "indices", "count logs", "mapping logs", "search logs", "get logs 1"}) {
            var result = provider().query(dead(), read, 10);
            assertFalse(result.message().contains("不支持的查询"),
                    "合法只读语句被白名单误伤: " + read + " -> " + result.message());
        }
    }

    @Test
    @DisplayName("search 语句按原文切分，大小写与多余空格不影响解析")
    void searchExpressionSlicedVerbatim() {
        // 大写索引名 + 尾随空格：曾经的 indexOf 反查写法会在这里失配并抛异常，
        // 现在应走到「连不上」而不是抛错
        var result = provider().query(dead(), "search MyIndex Foo  ", 10);
        assertNotNull(result);
        assertFalse(result.message().contains("不支持的查询"), "search 应通过白名单");
        // 不可达地址 => 连接类失败信息，而不是解析异常
        assertFalse(result.message().isBlank(), "失败原因不能为空");
    }

    @Test
    @DisplayName("不可达地址的连接测试返回失败对象（不抛异常）")
    void unreachableTestReturnsFail() {
        var result = provider().test(dead());
        assertNotNull(result);
        assertFalse(result.connected(), "不可达地址不应报告连通");
        assertFalse(result.message().isBlank(), "失败原因不能为空");
    }

    @Test
    @DisplayName("不可达地址下列索引/字段返回空列表而不是抛异常")
    void unreachableTreeReturnsEmpty() {
        assertTrue(provider().listTopNodes(dead()).isEmpty(), "不可达时顶层节点应为空列表");
        assertTrue(provider().listChildren(dead(), "logs").isEmpty(), "不可达时子节点应为空列表");
    }

    /** 指向本机未监听端口的连接参数：够快，又保证不会误连到别的服务 */
    private static MiddlewareConnectRequest dead() {
        return MiddlewareConnectRequest.of("127.0.0.1", 19092, "", "", "");
    }
}