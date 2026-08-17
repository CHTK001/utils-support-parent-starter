package com.chua.kcp.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.common.support.spi.annotations.Spi;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import kcp.ChannelConfig;
import kcp.KcpListener;
import kcp.KcpServer;
import kcp.Ukcp;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 基于 kcp-base 的 KCP 消息服务器实现（消息型，协议分类与 MQTT 一致）。
 *
 * <p>KCP 是基于 UDP 的可靠传输协议，本服务器在 KCP 之上提供主题发布、订阅、
 * 会话管理和消息下行推送能力，实现方式与 {@code TcpServer} 保持一致。</p>
 *
 * <p>通过 SPI 以 {@code "kcp"} 类型注册，可通过
 * {@code ServerBuilder.type("kcp")} 创建，消息协议采用 {@code topic:payload} 文本格式，
 * 客户端以 {@code register:clientId} 完成注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("kcp")
public class KcpServer extends AbstractServer {

    /**
     * KCP 会话标识（conv），两端保持一致
     */
    public static final int KCP_CONV = 0x4b4350;

    /**
     * KCP 最大传输单元（字节）
     */
    private static final int KCP_MTU = 512;

    /**
     * KCP 更新间隔（毫秒）
     */
    private static final int KCP_INTERVAL = 20;

    /**
     * KCP 快速重传阈值
     */
    private static final int KCP_FAST_RESEND = 2;

    /**
     * 客户端注册指令前缀
     */
    private static final String CMD_REGISTER = "register";

    /**
     * 注册确认指令前缀
     */
    private static final String CMD_REGISTERED = "registered:";

    /**
     * 通配订阅主题
     */
    private static final String TOPIC_WILDCARD = "#";

    /**
     * 客户端会话集合（clientId -> 会话）
     */
    private final Map<String, Ukcp> sessions = new HashMap<>();

    /**
     * 客户端元数据集合（clientId -> Map）
     */
    private final Map<String, Map<String, Object>> sessionMetadata = new HashMap<>();

    /**
     * 同步事件监听器列表
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 主题订阅处理器集合（topic -> handler 列表）
     */
    private final Map<String, List<BiConsumer<String, String>>> topicSubscribers = new ConcurrentHashMapAliasMap();

    /**
     * 连接事件处理器列表
     */
    private final List<Consumer<String>> connectListeners = new CopyOnWriteArrayList<>();

    /**
     * 断开事件处理器列表
     */
    private final List<Consumer<String>> disconnectListeners = new CopyOnWriteArrayList<>();

    /**
     * 错误事件处理器列表
     */
    private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();

    /**
     * OnOpen 注解方法列表
     */
    private final List<AnnotatedMethod> onOpenMethods = new CopyOnWriteArrayList<>();

    /**
     * OnClose 注解方法列表
     */
    private final List<AnnotatedMethod> onCloseMethods = new CopyOnWriteArrayList<>();

    /**
     * OnMessage 注解方法列表
     */
    private final List<AnnotatedMessage> onMessageMethods = new CopyOnWriteArrayList<>();

    /**
     * OnError 注解方法列表
     */
    private final List<AnnotatedMethod> onErrorMethods = new CopyOnWriteArrayList<>();

    /**
     * Netty 事件循环组（kcp-base 复用）
     */
    private EventLoopGroup eventLoopGroup;

    /**
     * kcp-base 服务器实例
     */
    private KcpServer kcpBaseServer;

    /**
     * KCP 配置（绑定到 kcp-base ChannelConfig）
     */
    private ChannelConfig channelConfig;

    /**
     * 创建 KCP 消息服务器。
     *
     * @param setting 服务器配置
     */
    public KcpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    public String getProtocol() {
        return "kcp";
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.KCP;
    }

    @Override
    protected void doStart() {
        setting.setProtocol("kcp");
        channelConfig = new ChannelConfig();
        channelConfig.setMtu(KCP_MTU);
        channelConfig.setTimeoutMillis(60_000L);
        channelConfig.nodelay(true, KCP_INTERVAL, KCP_FAST_RESEND, true);

        eventLoopGroup = new NioEventLoopGroup(setting.getBossThreads());
        channelConfig.setNettyBootstrapGroup(eventLoopGroup, NioDatagramChannel.class);

        kcpBaseServer = KcpServer.createStarted(channelConfig, new OAuthKcpListener(), setting.getPort());
        log.info("KCP 服务器启动: {}:{}", setting.getHost(), setting.getPort());
    }

    @Override
    protected void doStop() {
        if (kcpBaseServer != null) {
            for (Ukcp ukcp : sessions.values()) {
                try {
                    ukcp.close();
                } catch (Exception ignore) {
                }
            }
            sessions.clear();
            sessionMetadata.clear();
            topicSubscribers.clear();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
        }
        log.info("KCP 服务器停止");
    }

    /**
     * 向所有客户端广播消息。
     *
     * @param topic   主题
     * @param message 消息内容
     */
    public void publish(String topic, Object message) {
        String text = topic + ":" + message;
        for (Ukcp ukcp : sessions.values()) {
            sendTo(ukcp, text);
        }
        notifyListeners(listener -> listener.onMessage("broadcast", topic, String.valueOf(message)));
    }

    /**
     * 向指定客户端发送消息。
     *
     * @param clientId 客户端标识
     * @param topic    主题
     * @param message  消息内容
     */
    public void send(String clientId, String topic, Object message) {
        Ukcp ukcp = sessions.get(clientId);
        if (ukcp == null) {
            return;
        }
        sendTo(ukcp, topic + ":" + message);
    }

    private void sendTo(Ukcp ukcp, String text) {
        ByteBuf buf = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
        try {
            ukcp.write(buf);
        } finally {
            // kcp-base 会在内部 retain/release，调用方不再持有
        }
    }

    /**
     * 获取当前所有已连接的客户端标识列表。
     */
    public List<String> getConnectedClients() {
        return new ArrayList<>(sessions.keySet());
    }

    /**
     * 获取指定客户端的元数据。
     */
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = sessionMetadata.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    /**
     * 注册主题订阅处理器。
     */
    public KcpServer onSubscribe(String topic, BiConsumer<String, String> handler) {
        topicSubscribers.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    public void onConnect(Consumer<String> listener) {
        connectListeners.add(listener);
    }

    public void onDisconnect(Consumer<String> listener) {
        disconnectListeners.add(listener);
    }

    public void onError(Consumer<Throwable> listener) {
        errorListeners.add(listener);
    }

    @Override
    public KcpServer registerBean(Object handler) {
        super.registerBean(handler);
        if (handler == null) {
            return this;
        }
        Class<?> clazz = handler.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            method.setAccessible(true);
            if (method.isAnnotationPresent(OnOpen.class)) {
                onOpenMethods.add(new AnnotatedMethod(clazz, method));
            }
            if (method.isAnnotationPresent(OnClose.class)) {
                onCloseMethods.add(new AnnotatedMethod(clazz, method));
            }
            if (method.isAnnotationPresent(OnMessage.class)) {
                OnMessage annotation = method.getAnnotation(OnMessage.class);
                String topic = annotation.value();
                if (topic == null || topic.isEmpty()) {
                    topic = TOPIC_WILDCARD;
                }
                onMessageMethods.add(new AnnotatedMessage(clazz, method, topic));
            }
            if (method.isAnnotationPresent(OnError.class)) {
                onErrorMethods.add(new AnnotatedMethod(clazz, method));
            }
        }
        return this;
    }

    private void notifyListeners(Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyConnectListeners(String clientId) {
        for (Consumer<String> listener : connectListeners) {
            try {
                listener.accept(clientId);
            } catch (Exception e) {
                log.error("KCP 连接回调异常", e);
            }
        }
    }

    private void notifyDisconnectListeners(String clientId) {
        for (Consumer<String> listener : disconnectListeners) {
            try {
                listener.accept(clientId);
            } catch (Exception e) {
                log.error("KCP 断开回调异常", e);
            }
        }
    }

    private void notifyErrorListeners(Throwable cause) {
        for (Consumer<Throwable> listener : errorListeners) {
            try {
                listener.accept(cause);
            } catch (Exception e) {
                log.error("KCP 错误回调异常", e);
            }
        }
    }

    private void dispatchAnnotatedMethods(List<AnnotatedMethod> methods) {
        ObjectContext context = getObjectContext();
        if (context == null) {
            return;
        }
        for (AnnotatedMethod entry : methods) {
            Object bean = context.getBeanOfType(entry.beanClass());
            if (bean != null) {
                safeInvoke(bean, entry.method());
            }
        }
    }

    private void dispatchAnnotatedPublish(String topic, String payload) {
        ObjectContext context = getObjectContext();
        if (context == null) {
            return;
        }
        for (AnnotatedMessage entry : onMessageMethods) {
            if (matchTopic(entry.topic(), topic)) {
                Object bean = context.getBeanOfType(entry.beanClass());
                if (bean != null) {
                    safeInvoke(bean, entry.method(), payload);
                }
            }
        }
    }

    private void safeInvoke(Object bean, Method method, Object... args) {
        try {
            method.invoke(bean, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("KCP 注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), cause);
            notifyErrorListeners(cause);
        } catch (Exception e) {
            log.error("KCP 注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), e);
            notifyErrorListeners(e);
        }
    }

    private static boolean matchTopic(String pattern, String topic) {
        if (TOPIC_WILDCARD.equals(pattern)) {
            return true;
        }
        String[] pp = pattern.split("/");
        String[] tp = topic.split("/");
        int p = 0;
        int t = 0;
        while (p < pp.length && t < tp.length) {
            if (TOPIC_WILDCARD.equals(pp[p])) {
                return true;
            }
            if ("+".equals(pp[p]) || "*".equals(pp[p])) {
                p++;
                t++;
            } else if (pp[p].equals(tp[t])) {
                p++;
                t++;
            } else {
                return false;
            }
        }
        return p == pp.length && t == tp.length;
    }

    /**
     * kcp-base 的 KcpListener 实现，把会话与 OAuth 业务绑定。
     */
    private final class OAuthKcpListener implements KcpListener {

        @Override
        public void onConnected(Ukcp ukcp) {
            String initialId = ukcp.remoteAddress() != null
                    ? ukcp.remoteAddress().toString() : "client-" + ukcp.hashCode();
            sessions.put(initialId, ukcp);
            Map<String, Object> meta = new HashMap<>();
            meta.put("clientId", initialId);
            sessionMetadata.put(initialId, meta);
            // 用 kcp-base User 缓存 clientId（不持有 Channel，避免外部 Channel 失效）
            kcp.User user = ukcp.user();
            if (user != null) {
                user.setCache(initialId);
            }
            notifyListeners(listener -> listener.onClientConnected(initialId, meta));
            notifyConnectListeners(initialId);
            dispatchAnnotatedMethods(onOpenMethods);
        }

        @Override
        public void handleReceive(ByteBuf byteBuf, Ukcp ukcp) {
            try {
                String line = byteBuf.toString(StandardCharsets.UTF_8).trim();
                handleLine(ukcp, line);
            } finally {
                byteBuf.release();
            }
        }

        @Override
        public void handleException(Throwable ex, Ukcp ukcp) {
            log.error("KCP 连接处理异常: {}", ex.getMessage(), ex);
            notifyErrorListeners(ex);
        }

        @Override
        public void handleClose(Ukcp ukcp) {
            User user = ukcp.user();
            String clientId = user != null ? user.getClientId() : null;
            if (clientId != null) {
                sessions.remove(clientId);
                sessionMetadata.remove(clientId);
                notifyListeners(listener -> listener.onClientDisconnected(clientId));
                notifyDisconnectListeners(clientId);
                dispatchAnnotatedMethods(onCloseMethods);
            }
        }

        private void handleLine(Ukcp ukcp, String line) {
            if (line.isEmpty()) {
                return;
            }
            kcp.User user = ukcp.user();
            String clientId = user != null && user.getCache() instanceof String
                    ? (String) user.getCache() : null;
            if (clientId == null) {
                int colon = line.indexOf(':');
                clientId = colon > 0 ? line.substring(0, colon) : line;
            }
            String topic;
            String payload;
            int colon = line.indexOf(':');
            if (colon > 0) {
                topic = line.substring(0, colon);
                payload = line.substring(colon + 1);
            } else {
                topic = line;
                payload = "";
            }
            if (CMD_REGISTER.equals(topic)) {
                handleRegister(ukcp, payload);
                return;
            }
            dispatchMessage(clientId, topic, payload);
        }

        private void handleRegister(Ukcp ukcp, String clientId) {
            kcp.User user = ukcp.user();
            String oldId = user != null && user.getCache() instanceof String
                    ? (String) user.getCache() : null;
            if (clientId.isEmpty() || clientId.equals(oldId)) {
                sendTo(ukcp, CMD_REGISTERED + (oldId != null ? oldId : "client"));
                return;
            }
            if (oldId != null) {
                sessions.remove(oldId);
                sessionMetadata.remove(oldId);
                notifyListeners(listener -> listener.onClientDisconnected(oldId));
                notifyDisconnectListeners(oldId);
            }
            sessions.put(clientId, ukcp);
            Map<String, Object> meta = new HashMap<>();
            meta.put("clientId", clientId);
            sessionMetadata.put(clientId, meta);
            if (user != null) {
                user.setCache(clientId);
            }
            sendTo(ukcp, CMD_REGISTERED + clientId);
            notifyListeners(listener -> listener.onClientConnected(clientId, meta));
            notifyConnectListeners(clientId);
        }

        private void dispatchMessage(String clientId, String topic, String payload) {
            for (Map.Entry<String, List<BiConsumer<String, String>>> entry : topicSubscribers.entrySet()) {
                if (matchTopic(entry.getKey(), topic)) {
                    for (BiConsumer<String, String> handler : entry.getValue()) {
                        try {
                            handler.accept(topic, payload);
                        } catch (Exception e) {
                            log.error("KCP 订阅处理器异常", e);
                            notifyErrorListeners(e);
                        }
                    }
                }
            }
            dispatchAnnotatedPublish(topic, payload);
            notifyListeners(listener -> listener.onMessage(clientId, topic, payload));
        }
    }

    private record AnnotatedMethod(Class<?> beanClass, Method method) {
    }

    private record AnnotatedMessage(Class<?> beanClass, Method method, String topic) {
    }

    /**
     * 用 ConcurrentHashMap 包装 topic->handlers（避免再写一个类）。
     */
    private static final class ConcurrentHashMapAliasMap extends java.util.concurrent.ConcurrentHashMap<String, List<BiConsumer<String, String>>> {
    }

    /**
     * KCP 会话用户对象，挂在 Ukcp.user() 上。
     */
    public static final class User {
        private final String clientId;

        public User(String clientId) {
            this.clientId = clientId;
        }

        public String getClientId() {
            return clientId;
        }
    }
}
