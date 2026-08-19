package com.chua.ionet.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ionet.support.client.IonetSyncClient;
import com.chua.ionet.support.server.IonetSyncServer;
import com.iohao.net.extension.client.AbstractInputCommandRegion;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * ionet 事件总线分发器 — 接入 common {@link com.chua.common.support.concurrent.dispatcher.DispatcherProvider} 体系。
 *
 * <p>以 ionet 为底层网络的事件总线：服务端持有订阅定义，客户端发布消息，
 * 服务端按主题匹配后将消息投递给订阅方法（{@code definition.dispatch(body)}）。</p>
 *
 * <p>通过 SPI 以 {@code "ionet"} 类型注册，可用
 * {@code ServiceProvider.of(DispatcherProvider.class).getNewExtension("ionet", config)} 获取。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("ionet")
public class IonetDispatcherProvider extends AbstractDispatcherProvider {

    /** 服务端端口 */
    private static final int DEFAULT_PORT = 10100;

    /** 主题 -> 订阅定义列表 */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();
    /** 分发器服务端 */
    private IonetSyncServer server;
    /** 分发器客户端 */
    private IonetSyncClient client;

    /**
     * 创建 IonetDispatcherProvider 实例
     * @param config config
     */
    public IonetDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    @Override
    /** 开始 */
    public void start() {
        int port = resolvePort(config.getUrl());
        server = IonetSyncServer.builder()
                .port(port)
                .scanActionPackage(IonetDispatcherProvider.class)
                .build();
        // 服务端监听所有消息，按主题分发到订阅定义（SyncServerListener 为全 default 方法，需匿名类）
        server.addListener(new com.chua.common.support.network.server.SyncServerListener() {
            @Override
            /** OnMessage */
            public void onMessage(String clientId, String topic, Object message) {
                dispatchToDefinitions(topic, message);
            }
        });
        server.start();

        client = IonetSyncClient.builder()
                .host("127.0.0.1")
                .port(port)
                .addRegion(new EventRegion())
                .build();
        client.connect();
        if (!client.awaitConnection(10, TimeUnit.SECONDS)) {
            log.warn("[IonetDispatcherProvider] 客户端连接未就绪，主题分发可能延迟");
        }
        log.info("[IonetDispatcherProvider] Started eventbus on port={}", port);
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object body) {
        var definitions = definitionMap.get(topic);
        System.err.println("[IonetDispatcher] publish topic=" + topic + " definitions="
                + (definitions == null ? "null" : definitions.size())
                + " allTopics=" + definitionMap.keySet());
        if (definitions == null || definitions.isEmpty()) {
            return;
        }
        for (DispatcherDefinition definition : definitions) {
            try {
                System.err.println("[IonetDispatcher] dispatch -> " + definition);
                definition.dispatch(body);
                System.err.println("[IonetDispatcher] dispatch done");
            } catch (Exception e) {
                log.error("ionet 事件分发异常，主题：{}", topic, e);
                System.err.println("[IonetDispatcher] dispatch ERROR: " + e);
            }
        }
    }

    @Override
    /** 订阅 */
    public void subscribe(DispatcherDefinition definition) {
        for (String topic : definition.getTopics()) {
            definitionMap.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(definition);
        }
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(DispatcherDefinition definition) {
        for (String topic : definition.getTopics()) {
            List<DispatcherDefinition> definitions = definitionMap.get(topic);
            if (definitions != null) {
                definitions.remove(definition);
                if (definitions.isEmpty()) {
                    definitionMap.remove(topic);
                }
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        definitionMap.clear();
    }

    /**
     * 按主题触发订阅定义。
     *
     * @param topic   主题
     * @param message 消息体
     */
    private void dispatchToDefinitions(String topic, Object message) {
        var definitions = definitionMap.get(topic);
        if (definitions == null) {
            return;
        }
        for (DispatcherDefinition definition : definitions) {
            try {
                definition.dispatch(message);
            } catch (Exception e) {
                log.error("ionet 事件分发异常，主题：{}", topic, e);
            }
        }
    }

    /**
     * 从配置 URL 解析端口（ionet://host:port 或 host:port）。
     *
     * @param url 配置地址
     * @return 端口
     */
    private static int resolvePort(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_PORT;
        }
        String s = url;
        if (s.startsWith("ionet://")) {
            s = s.substring(8);
        }
        int idx = s.lastIndexOf(':');
        if (idx >= 0) {
            try {
                return Integer.parseInt(s.substring(idx + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_PORT;
    }

    /**
     * 事件 Region：供客户端连接使用（消息接收走服务端订阅回传，Region 仅占位）。
     */
    static class EventRegion extends AbstractInputCommandRegion {
        @Override
        /** 初始化InputCommand */
        public void initInputCommand() {
            // 空实现，不注册命令
        }
    }
}
