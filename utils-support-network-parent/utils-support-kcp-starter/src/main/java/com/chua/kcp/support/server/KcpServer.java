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
import io.jpower.kcp.netty.ChannelOptionHelper;
import io.jpower.kcp.netty.UkcpChannel;
import io.jpower.kcp.netty.UkcpChannelOption;
import io.jpower.kcp.netty.UkcpServerChannel;
import io.netty.bootstrap.UkcpServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 基于 kcp-netty 的 KCP 消息服务器实现（消息型，协议分类与 MQTT 一致）。
 *
 * <p>KCP 是基于 UDP 的可靠传输协议，本服务器在 KCP 之上提供主题发布、订阅、
 * 会话管理和消息下行推送能力，实现方式与 {@code TcpServer} 保持一致。</p>
 *
 * <p>通过 SPI 以 {@code "kcp"} 类型注册，可通过
 * {@code ServerBuilder.type("kcp")} 创建，消息协议采用 {@code topic:payload} 文本格式，
 * 客户端以 {@code register:clientId} 完成注册。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * ServerSetting setting = ServerSetting.defaults();
 * setting.setPort(19380);
 * KcpServer server = new KcpServer(setting);
 *
 * server.registerBean(new Object() {
 *     &#64;OnOpen
 *     public void onConnect() { }
 *
 *     &#64;OnMessage("order/#")
 *     public void onOrder(String payload) { }
 * });
 *
 * server.start();
 * server.publish("order", "hello");
 * server.stop();
 * }</pre>
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
     * 会话通道属性键，用于会话与 Channel 关联
     */
    private static final AttributeKey<KcpSession> SESSION_ATTR = AttributeKey.valueOf("kcp-session");

    /**
     * 客户端会话集合（clientId -> 会话）
     */
    private final Map<String, KcpSession> sessions = new ConcurrentHashMap<>();

    /**
     * 同步事件监听器列表
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 主题订阅处理器集合（topic -> handler 列表）
     */
    private final Map<String, List<BiConsumer<String, String>>> topicSubscribers = new ConcurrentHashMap<>();

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
     * Netty 事件循环组
     */
    private EventLoopGroup bossGroup;

    /**
     * KCP 服务端 Channel
     */
    private Channel serverChannel;

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
        bossGroup = new NioEventLoopGroup(setting.getBossThreads());
        UkcpServerBootstrap bootstrap = new UkcpServerBootstrap();
        bootstrap.group(bossGroup)
                .channel(UkcpServerChannel.class)
                .childOption(UkcpChannelOption.UKCP_MTU, KCP_MTU)
                .childHandler(new ChannelInitializer<UkcpChannel>() {
                    @Override
                    protected void initChannel(UkcpChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new KcpServerHandler());
                    }
                });
        ChannelOptionHelper.nodelay(bootstrap, true, KCP_INTERVAL, KCP_FAST_RESEND, true);
        try {
            ChannelFuture future = bootstrap.bind(setting.getHost(), setting.getPort()).sync();
            setting.setPort(((InetSocketAddress) future.channel().localAddress()).getPort());
            serverChannel = future.channel();
            log.info("KCP 服务器启动: {}:{}", setting.getHost(), setting.getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("KCP 服务器启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        // 先逐个关闭全部会话，避免 kcp-netty 关闭服务端通道时遍历并同时移除子通道集合引发并发修改异常
        for (KcpSession session : sessions.values()) {
            try {
                session.channel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sessions.clear();
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        topicSubscribers.clear();
        log.info("KCP 服务器停止");
    }

    /**
     * 向所有客户端广播消息。
     *
     * @param topic   主题
     * @param message 消息内容
     */
    public void publish(String topic, Object message) {
        for (KcpSession session : sessions.values()) {
            session.write(topic + ":" + message);
        }
        notifyListeners(listener -> listener.onMessage("broadcast", topic, message));
    }

    /**
     * 向指定客户端发送消息。
     *
     * @param clientId 客户端标识
     * @param topic    主题
     * @param message  消息内容
     */
    public void send(String clientId, String topic, Object message) {
        KcpSession session = sessions.get(clientId);
        if (session == null) {
            return;
        }
        session.write(topic + ":" + message);
    }

    /**
     * 获取当前所有已连接的客户端标识列表。
     *
     * @return 客户端标识列表
     */
    public List<String> getConnectedClients() {
        return new ArrayList<>(sessions.keySet());
    }

    /**
     * 获取指定客户端的元数据。
     *
     * @param clientId 客户端标识
     * @return 元数据映射，不存在时返回空 Map
     */
    public Map<String, Object> getClientMetadata(String clientId) {
        KcpSession session = sessions.get(clientId);
        return session != null ? Collections.unmodifiableMap(session.metadata) : Collections.emptyMap();
    }

    /**
     * 注册主题订阅处理器。
     *
     * @param topic   主题名称，支持通配符 # 和 +
     * @param handler 消息处理器
     * @return 当前服务器实例，支持链式调用
     */
    public KcpServer onSubscribe(String topic, BiConsumer<String, String> handler) {
        topicSubscribers.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 添加同步事件监听器。
     *
     * @param listener 监听器
     */
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除同步事件监听器。
     *
     * @param listener 监听器
     */
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    /**
     * 注册客户端连接回调。
     *
     * @param listener 连接回调
     */
    public void onConnect(Consumer<String> listener) {
        connectListeners.add(listener);
    }

    /**
     * 注册客户端断开回调。
     *
     * @param listener 断开回调
     */
    public void onDisconnect(Consumer<String> listener) {
        disconnectListeners.add(listener);
    }

    /**
     * 注册错误回调。
     *
     * @param listener 错误回调
     */
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

    /**
     * 通知同步事件监听器。
     *
     * @param action 监听器动作
     */
    private void notifyListeners(Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 通知连接回调。
     *
     * @param clientId 客户端标识
     */
    private void notifyConnectListeners(String clientId) {
        for (Consumer<String> listener : connectListeners) {
            try {
                listener.accept(clientId);
            } catch (Exception e) {
                log.error("KCP 连接回调异常", e);
            }
        }
    }

    /**
     * 通知断开回调。
     *
     * @param clientId 客户端标识
     */
    private void notifyDisconnectListeners(String clientId) {
        for (Consumer<String> listener : disconnectListeners) {
            try {
                listener.accept(clientId);
            } catch (Exception e) {
                log.error("KCP 断开回调异常", e);
            }
        }
    }

    /**
     * 通知错误回调。
     *
     * @param cause 异常
     */
    private void notifyErrorListeners(Throwable cause) {
        for (Consumer<Throwable> listener : errorListeners) {
            try {
                listener.accept(cause);
            } catch (Exception e) {
                log.error("KCP 错误回调异常", e);
            }
        }
    }

    /**
     * 分发无参注解方法。
     *
     * @param methods 注解方法列表
     */
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

    /**
     * 分发 OnMessage 注解方法。
     *
     * @param topic   消息主题
     * @param payload 消息内容
     */
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

    /**
     * 安全调用注解方法。
     *
     * @param bean    Bean 实例
     * @param method  方法
     * @param args    参数
     */
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

    /**
     * 匹配主题通配符（支持 #、* 和 +）。
     *
     * @param pattern 订阅模式
     * @param topic   实际主题
     * @return true 表示匹配
     */
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
     * KCP 服务端连接处理器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private final class KcpServerHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            UkcpChannel kcpChannel = (UkcpChannel) ctx.channel();
            kcpChannel.conv(KCP_CONV);
            String initialId = kcpChannel.remoteAddress() != null
                    ? kcpChannel.remoteAddress().toString() : "client-" + kcpChannel.hashCode();
            KcpSession session = new KcpSession(kcpChannel, initialId);
            ctx.channel().attr(SESSION_ATTR).set(session);
            sessions.put(initialId, session);
            notifyListeners(listener -> listener.onClientConnected(initialId, session.metadata));
            notifyConnectListeners(initialId);
            dispatchAnnotatedMethods(onOpenMethods);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buffer = (ByteBuf) msg;
            try {
                String line = buffer.toString(StandardCharsets.UTF_8);
                handleLine(ctx, line.trim());
            } finally {
                ReferenceCountUtil.release(buffer);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            KcpSession session = ctx.channel().attr(SESSION_ATTR).get();
            if (session != null && session.clientId != null) {
                sessions.remove(session.clientId);
                notifyListeners(listener -> listener.onClientDisconnected(session.clientId));
                notifyDisconnectListeners(session.clientId);
                dispatchAnnotatedMethods(onCloseMethods);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("KCP 连接处理异常: {}", cause.getMessage(), cause);
            notifyErrorListeners(cause);
            ctx.close();
        }

        /**
         * 处理一行消息。
         *
         * @param ctx  通道上下文
         * @param line 消息内容
         */
        private void handleLine(ChannelHandlerContext ctx, String line) {
            if (line.isEmpty()) {
                return;
            }
            KcpSession session = ctx.channel().attr(SESSION_ATTR).get();
            if (session == null) {
                return;
            }
            int colon = line.indexOf(':');
            String topic = colon > 0 ? line.substring(0, colon) : line;
            String payload = colon > 0 ? line.substring(colon + 1) : "";
            if (CMD_REGISTER.equals(topic)) {
                handleRegister(session, payload);
                return;
            }
            dispatchMessage(session, topic, payload);
        }

        /**
         * 处理客户端注册指令。
         *
         * @param session  会话
         * @param clientId 客户端标识
         */
        private void handleRegister(KcpSession session, String clientId) {
            String oldId = session.clientId;
            if (clientId.isEmpty() || clientId.equals(oldId)) {
                session.write(CMD_REGISTERED + oldId);
                return;
            }
            sessions.remove(oldId);
            session.clientId = clientId;
            session.metadata.put("clientId", clientId);
            sessions.put(clientId, session);
            session.write(CMD_REGISTERED + clientId);
            notifyListeners(listener -> listener.onClientConnected(clientId, session.metadata));
        }

        /**
         * 分发消息：本地订阅处理、注解分发、监听器通知。
         *
         * @param session 会话
         * @param topic   主题
         * @param payload 消息内容
         */
        private void dispatchMessage(KcpSession session, String topic, String payload) {
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
            notifyListeners(listener -> listener.onMessage(session.clientId, topic, payload));
        }
    }

    /**
     * KCP 客户端会话。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private final class KcpSession {

        /**
         * 底层 KCP 通道
         */
        private final UkcpChannel channel;

        /**
         * 客户端标识
         */
        private volatile String clientId;

        /**
         * 客户端元数据
         */
        private final Map<String, Object> metadata = new HashMap<>();

        /**
         * 创建会话。
         *
         * @param channel  底层 KCP 通道
         * @param clientId 客户端标识
         */
        private KcpSession(UkcpChannel channel, String clientId) {
            this.channel = channel;
            this.clientId = clientId;
            this.metadata.put("clientId", clientId);
        }

        /**
         * 发送一条消息。
         *
         * @param message 消息内容
         */
        private void write(String message) {
            channel.writeAndFlush(Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
        }
    }

    /**
     * 注解方法定义。
     *
     * @param beanClass Bean 类型
     * @param method    注解方法
     * @author CH
     * @since 4.0.0.42
     */
    private record AnnotatedMethod(Class<?> beanClass, Method method) {
    }

    /**
     * OnMessage 注解方法定义。
     *
     * @param beanClass Bean 类型
     * @param method    注解方法
     * @param topic     订阅主题
     * @author CH
     * @since 4.0.0.42
     */
    private record AnnotatedMessage(Class<?> beanClass, Method method, String topic) {
    }
}
