package com.chua.runtime.e2e;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.handler.JedisHandler;
import com.chua.runtime.apm.handler.KafkaHandler;
import com.chua.runtime.apm.handler.ZooKeeperHandler;
import com.chua.runtime.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 应用层 Handler 单元测试 — 验证 ZK/Jedis/Kafka Handler 在零依赖情况下的初始化 / 启动 / 停止。
 *
 * @author CH
 * @since 4.0.0.42
 */
class AppLayerHandlersTest {

    @Test
    void zooKeeperHandlerInitStartStop() throws Exception {
        ZooKeeperHandler handler = new ZooKeeperHandler();
        assertEquals("zk-handler", handler.name());
        assertEquals("1.0.0", handler.version());
        handler.init(new PluginContext(Paths.get(System.getProperty("java.io.tmpdir"))));
        handler.start();
        assertTrue(handler.isRunning());
        handler.stop();
        assertFalse(handler.isRunning());
        // 空记录
        assertEquals(0, handler.getRecords().size());
    }

    @Test
    void jedisHandlerInitStartStop() throws Exception {
        JedisHandler handler = new JedisHandler();
        assertEquals("jedis-handler", handler.name());
        handler.init(new PluginContext(Paths.get(System.getProperty("java.io.tmpdir"))));
        handler.start();
        assertTrue(handler.isRunning());
        handler.stop();
        assertFalse(handler.isRunning());
    }

    @Test
    void kafkaHandlerInitStartStop() throws Exception {
        KafkaHandler handler = new KafkaHandler();
        assertEquals("kafka-handler", handler.name());
        handler.init(new PluginContext(Paths.get(System.getProperty("java.io.tmpdir"))));
        handler.start();
        assertTrue(handler.isRunning());
        handler.stop();
        assertFalse(handler.isRunning());
    }

    @Test
    void dependencyGraphHandlerAccumulatesEdges() throws Exception {
        DependencyGraphHandler handler = new DependencyGraphHandler();
        handler.init(new PluginContext(Paths.get(System.getProperty("java.io.tmpdir"))));
        handler.start();
        com.chua.runtime.protocol.Endpoint source = com.chua.runtime.protocol.Endpoint.builder()
                .kind(com.chua.runtime.protocol.EndpointKind.CLIENT)
                .protocol(com.chua.runtime.protocol.Protocol.HTTP)
                .software(com.chua.runtime.protocol.Software.JEDIS)
                .host("localhost")
                .port(8080)
                .path("/")
                .build();
        com.chua.runtime.protocol.Endpoint target = com.chua.runtime.protocol.Endpoint.builder()
                .kind(com.chua.runtime.protocol.EndpointKind.SERVER)
                .protocol(com.chua.runtime.protocol.Protocol.REDIS)
                .software(com.chua.runtime.protocol.Software.JEDIS)
                .host("redis-host")
                .port(6379)
                .path("/")
                .build();
        handler.record(source, target, com.chua.runtime.protocol.Protocol.REDIS,
                com.chua.runtime.protocol.Software.JEDIS, 10, false, null);
        handler.record(source, target, com.chua.runtime.protocol.Protocol.REDIS,
                com.chua.runtime.protocol.Software.JEDIS, 20, true, "ConnectionRefused");
        assertEquals(1, handler.getEdges().size());
        com.chua.runtime.protocol.DependencyEdge edge = handler.getEdges().get(0);
        assertEquals(2, edge.getCallCount());
        assertEquals(1, edge.getErrorCount());
        assertEquals(15.0, edge.avgDuration(), 0.01);
        assertEquals("ConnectionRefused", edge.getLastError());
    }

    @Test
    void apmBootstrapIncludesAllDefaultHandlers() throws Exception {
        ApmBootstrap bootstrap = new ApmBootstrap(Paths.get(System.getProperty("java.io.tmpdir")));
        bootstrap.start();
        try {
            assertNotNull(bootstrap.getHandler(ZooKeeperHandler.class));
            assertNotNull(bootstrap.getHandler(JedisHandler.class));
            assertNotNull(bootstrap.getHandler(KafkaHandler.class));
            assertEquals(10, bootstrap.getHandlers().size());
        } finally {
            bootstrap.stop();
        }
    }
}