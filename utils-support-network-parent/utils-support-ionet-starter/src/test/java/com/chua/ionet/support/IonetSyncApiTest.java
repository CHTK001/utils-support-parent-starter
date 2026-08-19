package com.chua.ionet.support;

import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.ionet.support.client.IonetSyncClient;
import com.chua.ionet.support.server.IonetSyncServer;
import com.iohao.net.extension.client.AbstractInputCommandRegion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IonetSyncServer / IonetSyncClient 接入 SyncServer / SyncClient 接口测试。
 *
 * <p>验证 sync 体系接口实现（publish/send 通知监听器、subscribe 注册、未连接守卫），
 * 不实际启动 iohao 服务器（避免 JVM 参数依赖）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class IonetSyncApiTest {

    /**
     * 空 Region：满足 IonetSyncClient.Builder 的 region 校验。
     */
    static class EmptyRegion extends AbstractInputCommandRegion {
        @Override
        /** 初始化InputCommand */
        public void initInputCommand() {
            // 空实现，不注册命令
        }
    }

    @Test
    void syncServerImplementsInterface() {
        IonetSyncServer server = IonetSyncServer.builder()
                .port(10100)
                .scanActionPackage(IonetSyncApiTest.class)
                .build();
        assertTrue(server instanceof SyncServer, "IonetSyncServer 应实现 SyncServer 接口");
    }

    @Test
    void syncServerPublishNotifiesListener() {
        IonetSyncServer server = IonetSyncServer.builder()
                .port(10100)
                .scanActionPackage(IonetSyncApiTest.class)
                .build();
        List<String> received = new ArrayList<>();
        server.addListener(new SyncServerListener() {
            @Override
            /** OnMessage */
            public void onMessage(String clientId, String topic, Object message) {
                received.add(topic + ":" + message);
            }
        });

        server.publish("order/ann", "hello");
        assertEquals(List.of("order/ann:hello"), received, "publish 应广播通知监听器");
    }

    @Test
    void syncServerSendNotifiesListenerWithClientId() {
        IonetSyncServer server = IonetSyncServer.builder()
                .port(10100)
                .scanActionPackage(IonetSyncApiTest.class)
                .build();
        AtomicReference<String> seen = new AtomicReference<>();
        server.addListener(new SyncServerListener() {
            @Override
            /** OnMessage */
            public void onMessage(String clientId, String topic, Object message) {
                seen.set(clientId + "|" + topic + "|" + message);
            }
        });

        server.send("client-1", "reply/ann", "pong");
        assertEquals("client-1|reply/ann|pong", seen.get(), "send 应带 clientId 定向通知监听器");
    }

    @Test
    void syncClientImplementsInterface() {
        IonetSyncClient client = IonetSyncClient.builder()
                .host("127.0.0.1")
                .port(10100)
                .addRegion(new EmptyRegion())
                .build();
        assertTrue(client instanceof SyncClient, "IonetSyncClient 应实现 SyncClient 接口");
    }

    @Test
    void syncClientSubscribeAndSendGuards() {
        IonetSyncClient client = IonetSyncClient.builder()
                .host("127.0.0.1")
                .port(10100)
                .addRegion(new EmptyRegion())
                .build();
        // 未连接时 send 应抛 IllegalStateException（连接守卫）
        assertFalse(client.isConnected(), "未 startup 时应为未连接");
        assertThrows(IllegalStateException.class, () -> client.send("topic", "msg"),
                "未连接 send 应抛 IllegalStateException");

        // subscribe 可先注册（与 SyncClient 语义一致）
        AtomicReference<String> seen = new AtomicReference<>();
        client.subscribe("order/#", (topic, payload) -> seen.set(topic + ":" + payload));
        assertTrue(seen.get() == null, "未连接时订阅不应立即触发");
    }
}
