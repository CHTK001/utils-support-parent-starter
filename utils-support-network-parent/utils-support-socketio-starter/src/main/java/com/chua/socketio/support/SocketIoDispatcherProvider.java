package com.chua.socketio.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import io.socket.client.IO;
import io.socket.client.Socket;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Socket.IO 分发器提供者，基于 Socket.io-客户端 连接远程 Socket.IO 服务。
 * <p>
 * 作为客户端接入远程 Socket.IO 服务，通过事件机制实现 topic 级别的消息分发。
 * 本地不启动任何服务器，所有连接都指向外部已部署的 Socket.IO 服务。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("socketio")
public class SocketIoDispatcherProvider extends AbstractDispatcherProvider {

    /**
     * 远程 Socket.IO 客户端连接
     */
    private Socket socket;

    /**
     * topic 到订阅定义的映射
     */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /**
     * 已注册的事件集合，避免重复注册同一个事件
     */
    private final java.util.Set<String> registeredTopics = new CopyOnWriteArraySet<>();

    /**
     * 构造 Socket.IO 分发器提供者。
     *
     * @param config 分发器配置
     */
    public SocketIoDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    /** 开始 */
    public void start() {
        if (socket != null && socket.connected()) {
            return;
        }
        String url = buildUrl();
        IO.Options options = new IO.Options();
        options.reconnection = true;
        options.reconnectionAttempts = Integer.MAX_VALUE;
        options.reconnectionDelay = 1000;
        options.reconnectionDelayMax = 5000;
        long timeout = config.getConnectionTimeoutMillis() > 0
                ? config.getConnectionTimeoutMillis() : 5000;
        if (timeout > Integer.MAX_VALUE) {
            timeout = Integer.MAX_VALUE;
        }
        options.timeout = (int) timeout;
        try {
            socket = IO.socket(url, options);
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("SocketIO 连接地址非法: " + url, e);
        }
        socket.on(Socket.EVENT_CONNECT, args -> {
            log.info("[SocketIO] 已连接远程服务: {}", url);
            for (String topic : definitionMap.keySet()) {
                registerEventListener(topic);
            }
        });
        socket.on(Socket.EVENT_DISCONNECT, args -> {
            log.info("[SocketIO] 与远程服务断开: {}", url);
        });
        socket.connect();
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object body) {
        if (socket == null || !socket.connected()) {
            log.warn("[SocketIO] 未连接远程服务，无法发布消息: topic={}", topic);
            return;
        }
        String message = body == null ? "" : body.toString();
        socket.emit(topic, message);
        log.debug("[SocketIO] 已发布消息: topic={}, body={}", topic, message);
    }

    @Override
    /** 订阅 */
    public void subscribe(DispatcherDefinition definition) {
        for (String topic : definition.getTopics()) {
            definitionMap.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(definition);
            if (socket != null && socket.connected()) {
                registerEventListener(topic);
            }
            log.debug("[SocketIO] 已订阅主题: {}", topic);
        }
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(DispatcherDefinition definition) {
        for (String topic : definition.getTopics()) {
            List<DispatcherDefinition> defs = definitionMap.get(topic);
            if (defs != null) {
                defs.remove(definition);
                if (defs.isEmpty()) {
                    definitionMap.remove(topic);
                }
            }
            log.debug("[SocketIO] 已取消订阅主题: {}", topic);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (socket != null) {
            socket.disconnect();
            socket.close();
            socket = null;
            log.info("[SocketIO] 分发器客户端已关闭");
        }
        definitionMap.clear();
        registeredTopics.clear();
    }

    /**
     * 根据配置构建远程服务地址。
     *
     * @return 形如 http://host:port 的地址
     */
    private String buildUrl() {
        String url = config.getUrl();
        String host = "localhost";
        int port = 9092;
        if (url != null && !url.isEmpty()) {
            url = url.replaceAll("^socketio://", "")
                    .replaceAll("^http://", "")
                    .replaceAll("^https://", "");
            String[] parts = url.split(":");
            if (parts.length >= 2) {
                host = parts[0];
                try {
                    port = Integer.parseInt(parts[1].replaceAll("/.*", ""));
                } catch (NumberFormatException ignored) {
                    // 端口解析失败时使用默认端口
                }
            } else if (parts.length == 1 && !parts[0].isEmpty()) {
                host = parts[0];
            }
        }
        return "http://" + host + ":" + port;
    }

    /**
     * 注册 Socket.IO 事件监听器。
     * <p>
     * 同一个 topic 只注册一次，避免重复绑定。
     * </p>
     *
     * @param topic 主题名称
     */
    private void registerEventListener(String topic) {
        if (!registeredTopics.add(topic)) {
            return;
        }
        socket.on(topic, args -> {
            if (args == null || args.length == 0) {
                return;
            }
            Object data = args[0];
            List<DispatcherDefinition> defs = definitionMap.get(topic);
            if (defs != null) {
                for (DispatcherDefinition def : defs) {
                    try {
                        def.dispatch(data);
                    } catch (Exception e) {
                        log.error("[SocketIO] 分发事件失败: topic={}", topic, e);
                    }
                }
            }
        });
    }
}
