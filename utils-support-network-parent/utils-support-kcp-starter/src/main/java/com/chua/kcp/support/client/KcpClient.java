package com.chua.kcp.support.client;

import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.kcp.support.server.KcpServer;
import io.jpower.kcp.netty.ChannelOptionHelper;
import io.jpower.kcp.netty.UkcpChannel;
import io.jpower.kcp.netty.UkcpChannelOption;
import io.jpower.kcp.netty.UkcpClientChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 基于 kcp-netty 的 KCP 消息客户端实现，与 {@link KcpServer} 配对使用。
 *
 * <p>通过 KCP 可靠 UDP 长连接与服务端双向同步，支持注册、主题订阅、
 * 消息收发以及 {@code OnOpen}/{@code OnMessage}/{@code OnClose}/{@code OnError} 注解分发。
 * 消息协议采用 {@code topic:payload} 文本格式，客户端以 {@code register:clientId} 完成注册。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * KcpClient client = new KcpClient("kcp://127.0.0.1:19380");
 * client.subscribe("order/#", (topic, payload) -> System.out.println("收到: " + payload));
 * client.connect();
 * client.send("greet", "hello");
 * client.close();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class KcpClient {

    /**
     * 注册指令前缀
     */
    private static final String CMD_REGISTER = "register:";

    /**
     * 注册确认主题
     */
    private static final String CMD_REGISTERED = "registered";

    /**
     * 等待注册确认的超时时间（毫秒）
     */
    private static final long REGISTER_TIMEOUT_MS = 3000L;

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
     * 默认服务端端口
     */
    private static final int DEFAULT_PORT = 19380;

    /**
     * 请求主题前缀
     */
    private static final String REQ_PREFIX = "req/";

    /**
     * 响应主题前缀
     */
    private static final String RESP_PREFIX = "resp/";

    /**
     * 请求标识与消息体的分隔符
     */
    private static final String SEPARATOR = "|";

    /**
     * 默认请求执行超时时间（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    /**
     * 通配订阅主题
     */
    private static final String TOPIC_WILDCARD = "#";

    /**
     * 客户端标识
     */
    private final String clientId;

    /**
     * 服务端地址
     */
    private final String serverUrl;

    /**
     * 主题订阅处理器集合（topic -> handler 列表）
     */
    private final Map<String, List<BiConsumer<String, String>>> topicSubscribers = new ConcurrentHashMap<>();

    /**
     * 全局消息监听器列表
     */
    private final List<BiConsumer<String, String>> messageListeners = new CopyOnWriteArrayList<>();

    /**
     * 待处理的请求映射（requestId -> 响应 Future）
     */
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 连接成功回调列表
     */
    private final List<Consumer<String>> connectListeners = new CopyOnWriteArrayList<>();

    /**
     * 断开回调列表
     */
    private final List<Consumer<String>> disconnectListeners = new CopyOnWriteArrayList<>();

    /**
     * 错误回调列表
     */
    private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();

    /**
     * 注册的 Bean 实例集合
     */
    private final Map<Class<?>, Object> beans = new ConcurrentHashMap<>();

    /**
     * OnOpen 注解方法列表
     */
    private final List<AnnotatedMethod> onOpenMethods = new CopyOnWriteArrayList<>();

    /**
     * OnMessage 注解方法列表
     */
    private final List<AnnotatedMessage> onMessageMethods = new CopyOnWriteArrayList<>();

    /**
     * OnClose 注解方法列表
     */
    private final List<AnnotatedMethod> onCloseMethods = new CopyOnWriteArrayList<>();

    /**
     * OnError 注解方法列表
     */
    private final List<AnnotatedMethod> onErrorMethods = new CopyOnWriteArrayList<>();

    /**
     * Netty 事件循环组
     */
    private EventLoopGroup group;

    /**
     * 底层 KCP 通道
     */
    private UkcpChannel channel;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 是否已注册成功
     */
    private volatile boolean registered;

    /**
     * 创建 KCP 客户端。
     *
     * @param serverUrl 服务端地址，如 kcp://localhost:19380
     */
    public KcpClient(String serverUrl) {
        this(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建 KCP 客户端。
     *
     * @param clientId  客户端标识
     * @param serverUrl 服务端地址
     */
    public KcpClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
    }

    /**
     * 连接到 KCP 服务端。
     */
    public void connect() {
        if (connected) {
            return;
        }
        group = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(UkcpClientChannel.class)
                .option(UkcpChannelOption.UKCP_MTU, KCP_MTU)
                .handler(new ChannelInitializer<UkcpChannel>() {
                    @Override
                    protected void initChannel(UkcpChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new KcpClientHandler());
                    }
                });
        ChannelOptionHelper.nodelay(bootstrap, true, KCP_INTERVAL, KCP_FAST_RESEND, true);
        try {
            ChannelFuture future = bootstrap.connect(host(), port()).sync();
            channel = (UkcpChannel) future.channel();
            channel.conv(KcpServer.KCP_CONV);
            connected = true;
            sendLine(CMD_REGISTER + clientId);
            waitRegistered();
            notifyConnectListeners(clientId);
            dispatchAnnotatedMethods(onOpenMethods);
        } catch (Exception e) {
            connected = false;
            shutdownGroup();
            throw new RuntimeException("KCP 客户端连接失败: " + serverUrl, e);
        }
    }

    /**
     * 等待服务端注册确认，保证 connect() 返回后已可收发。
     */
    private void waitRegistered() {
        long deadline = System.currentTimeMillis() + REGISTER_TIMEOUT_MS;
        while (!registered && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 断开与服务端的连接。
     */
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        if (channel != null) {
            channel.close();
            channel = null;
        }
        shutdownGroup();
        notifyDisconnectListeners(clientId);
        dispatchAnnotatedMethods(onCloseMethods);
    }

    /**
     * 判断客户端是否已连接。
     *
     * @return true 表示已连接
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 获取客户端标识。
     *
     * @return 客户端标识
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * 向指定主题发送消息。
     *
     * @param topic   主题
     * @param message 消息内容
     */
    public void send(String topic, Object message) {
        checkConnected();
        sendLine(topic + ":" + message);
    }

    /**
     * 向指定主题发送消息（publish 语义与 send 一致）。
     *
     * @param topic   主题
     * @param message 消息内容
     */
    public void publish(String topic, Object message) {
        send(topic, message);
    }

    /**
     * 同步执行请求并等待服务端响应（类似 HTTP 请求-响应）。
     *
     * <p>将消息发送到 {@code req/topic} 主题，并在 {@code resp/topic} 主题上等待
     * 匹配的响应，请求标识由客户端自动生成并随消息携带。</p>
     *
     * @param topic   业务主题
     * @param message 请求消息
     * @return 服务端响应内容
     */
    public String execute(String topic, Object message) {
        return execute(topic, message, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 同步执行请求并等待服务端响应，指定超时时间。
     *
     * @param topic     业务主题
     * @param message   请求消息
     * @param timeoutMs 超时时间（毫秒），大于 0 时生效
     * @return 服务端响应内容
     * @throws RuntimeException 超时或执行失败时抛出
     */
    public String execute(String topic, Object message, long timeoutMs) {
        CompletableFuture<String> future = executeAsync(topic, message, timeoutMs);
        try {
            return future.get(timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("KCP 请求执行失败: " + topic, e);
        }
    }

    /**
     * 异步执行请求，返回响应 Future。
     *
     * @param topic   业务主题
     * @param message 请求消息
     * @return 异步任务，完成时包含服务端响应内容
     */
    public CompletableFuture<String> executeAsync(String topic, Object message) {
        return executeAsync(topic, message, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 异步执行请求，返回响应 Future，指定超时时间。
     *
     * @param topic     业务主题
     * @param message   请求消息
     * @param timeoutMs 超时时间（毫秒），大于 0 时生效
     * @return 异步任务，完成时包含服务端响应内容
     */
    public CompletableFuture<String> executeAsync(String topic, Object message, long timeoutMs) {
        checkConnected();
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        future.whenComplete((result, error) -> pendingRequests.remove(requestId));
        sendLine(REQ_PREFIX + topic + ":" + requestId + SEPARATOR + message);
        if (timeoutMs > 0) {
            future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        }
        return future;
    }

    /**
     * 订阅指定主题的消息。
     *
     * @param topic   主题名称，支持通配符 # 和 +
     * @param handler 消息处理器
     * @return 当前客户端实例，支持链式调用
     */
    public KcpClient subscribe(String topic, BiConsumer<String, String> handler) {
        topicSubscribers.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 取消订阅指定主题。
     *
     * @param topic 主题名称
     */
    public void unsubscribe(String topic) {
        topicSubscribers.remove(topic);
    }

    /**
     * 注册全局消息监听器。
     *
     * @param listener 消息监听器
     * @return 当前客户端实例，支持链式调用
     */
    public KcpClient onMessage(BiConsumer<String, String> listener) {
        messageListeners.add(listener);
        return this;
    }

    /**
     * 注册连接成功回调。
     *
     * @param listener 回调，参数为客户端标识
     * @return 当前客户端实例，支持链式调用
     */
    public KcpClient onConnect(Consumer<String> listener) {
        connectListeners.add(listener);
        return this;
    }

    /**
     * 注册断开回调。
     *
     * @param listener 回调，参数为客户端标识
     * @return 当前客户端实例，支持链式调用
     */
    public KcpClient onDisconnect(Consumer<String> listener) {
        disconnectListeners.add(listener);
        return this;
    }

    /**
     * 注册错误回调。
     *
     * @param listener 错误回调
     * @return 当前客户端实例，支持链式调用
     */
    public KcpClient onError(Consumer<Throwable> listener) {
        errorListeners.add(listener);
        return this;
    }

    /**
     * 注册 Bean，扫描并缓存 {@code OnOpen}/{@code OnMessage}/{@code OnClose}/{@code OnError} 注解方法。
     *
     * @param bean Bean 实例
     * @return 当前客户端实例，支持链式调用
     */
    public KcpClient registerBean(Object bean) {
        if (bean == null) {
            return this;
        }
        beans.put(bean.getClass(), bean);
        Class<?> clazz = bean.getClass();
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
     * 获取客户端元数据。
     *
     * @return 元数据映射
     */
    public Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "kcp");
    }

    /**
     * 关闭客户端，释放资源。
     */
    public void close() {
        disconnect();
    }

    /**
     * 处理一行消息。
     *
     * @param line 消息行
     */
    private void handleLine(String line) {
        String message = line.trim();
        int colon = message.indexOf(':');
        String topic = colon > 0 ? message.substring(0, colon) : message;
        String payload = colon > 0 ? message.substring(colon + 1) : "";
        if (CMD_REGISTERED.equals(topic)) {
            registered = true;
            return;
        }
        dispatchMessage(topic, payload);
    }

    /**
     * 分发消息：请求响应匹配、订阅处理、注解分发、全局监听器。
     *
     * @param topic   主题
     * @param payload 消息内容
     */
    private void dispatchMessage(String topic, String payload) {
        if (topic.startsWith(RESP_PREFIX)) {
            handleResponse(topic, payload);
            return;
        }
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
        for (BiConsumer<String, String> listener : messageListeners) {
            try {
                listener.accept(topic, payload);
            } catch (Exception e) {
                log.error("KCP 全局消息监听器异常", e);
                notifyErrorListeners(e);
            }
        }
    }

    /**
     * 处理请求响应消息，匹配待处理请求并完成对应 Future。
     *
     * <p>响应消息格式为 {@code resp/topic:requestId|response}。</p>
     *
     * @param topic   响应主题
     * @param payload 响应内容
     */
    private void handleResponse(String topic, String payload) {
        int separatorIndex = payload.indexOf(SEPARATOR);
        if (separatorIndex <= 0) {
            log.warn("KCP 响应消息格式错误: {}", payload);
            return;
        }
        String requestId = payload.substring(0, separatorIndex);
        String response = payload.substring(separatorIndex + 1);
        CompletableFuture<String> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(response);
        }
    }

    /**
     * 发送一行消息。
     *
     * @param line 消息行
     */
    private void sendLine(String line) {
        if (channel == null) {
            throw new IllegalStateException("客户端未连接");
        }
        channel.writeAndFlush(Unpooled.copiedBuffer(line, StandardCharsets.UTF_8));
    }

    /**
     * 校验连接状态。
     */
    private void checkConnected() {
        if (!connected) {
            throw new IllegalStateException("客户端未连接");
        }
    }

    /**
     * 关闭事件循环组。
     */
    private void shutdownGroup() {
        if (group != null) {
            group.shutdownGracefully().syncUninterruptibly();
            group = null;
        }
    }

    /**
     * 解析服务端主机地址。
     *
     * @return 主机名
     */
    private String host() {
        String address = stripScheme(serverUrl);
        int colon = address.lastIndexOf(':');
        return colon > 0 ? address.substring(0, colon) : "127.0.0.1";
    }

    /**
     * 解析服务端端口。
     *
     * @return 端口号
     */
    private int port() {
        String address = stripScheme(serverUrl);
        int colon = address.lastIndexOf(':');
        return colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : DEFAULT_PORT;
    }

    /**
     * 去除地址的协议前缀。
     *
     * @param url 原始地址
     * @return 去除协议前缀后的地址
     */
    private static String stripScheme(String url) {
        String address = url;
        if (address.startsWith("kcp://")) {
            address = address.substring("kcp://".length());
        }
        return address;
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
     * 通知连接成功回调。
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
        for (AnnotatedMethod entry : methods) {
            Object bean = beans.get(entry.beanClass());
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
        for (AnnotatedMessage entry : onMessageMethods) {
            if (matchTopic(entry.topic(), topic)) {
                Object bean = beans.get(entry.beanClass());
                if (bean != null) {
                    safeInvoke(bean, entry.method(), payload);
                }
            }
        }
    }

    /**
     * 安全调用注解方法。
     *
     * @param bean   Bean 实例
     * @param method 方法
     * @param args   参数
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
     * KCP 客户端连接处理器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private final class KcpClientHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            UkcpChannel kcpChannel = (UkcpChannel) ctx.channel();
            kcpChannel.conv(KcpServer.KCP_CONV);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buffer = (ByteBuf) msg;
            try {
                handleLine(buffer.toString(StandardCharsets.UTF_8));
            } finally {
                ReferenceCountUtil.release(buffer);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (connected) {
                connected = false;
                notifyDisconnectListeners(clientId);
                dispatchAnnotatedMethods(onCloseMethods);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("KCP 连接处理异常: {}", cause.getMessage(), cause);
            notifyErrorListeners(cause);
            ctx.close();
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
