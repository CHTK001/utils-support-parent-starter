package com.chua.kcp.support.client;

import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.kcp.support.server.KcpServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import kcp.ChannelConfig;
import kcp.KcpListener;
import kcp.Ukcp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 基于 kcp-base 的 KCP 消息客户端实现，与 {@link KcpServer} 配对使用。
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
public class KcpClient {

    private static final Logger log = LoggerFactory.getLogger(KcpClient.class);

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
     * 请求与响应分隔符
     */
    private static final String SEPARATOR = "|";

    /**
     * 等待响应超时（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    /**
     * 客户端标识
     */
    private final String clientId;

    /**
     * 服务端 URL（kcp://host:port）
     */
    private final String serverUrl;

    /**
     * Netty 事件循环组（kcp-base 复用）
     */
    private EventLoopGroup eventLoopGroup;

    /**
     * kcp-base 客户端实例
     */
    private kcp.KcpClient kcpBaseClient;

    /**
     * 当前 Ukcp 会话
     */
    private volatile Ukcp session;

    /**
     * 待响应 Future 表（requestId -> Future）
     */
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 主题订阅处理器表（topic -> handler 列表）
     */
    private final Map<String, List<BiConsumer<String, String>>> topicSubscribers = new ConcurrentHashMap<>();

    /**
     * 客户端元数据
     */
    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * 已注册的注解 Bean（beanClass -> 实例）
     */
    private final Map<Class<?>, Object> annotatedBeans = new ConcurrentHashMap<>();

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
     * 消息监听器列表
     */
    private final List<BiConsumer<String, String>> messageListeners = new CopyOnWriteArrayList<>();

    /**
     * 连接监听器列表
     */
    private final List<Consumer<String>> connectListeners = new CopyOnWriteArrayList<>();

    /**
     * 断开监听器列表
     */
    private final List<Consumer<String>> disconnectListeners = new CopyOnWriteArrayList<>();

    /**
     * 错误监听器列表
     */
    private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();

    /**
     * 注册确认 Future
     */
    private volatile CompletableFuture<Void> registerFuture;

    /**
     * 客户端 ID 计数器
     */
    private static final AtomicLong CLIENT_COUNTER = new AtomicLong();

    public KcpClient(String serverUrl) {
        this("kcp-client-" + CLIENT_COUNTER.incrementAndGet(), serverUrl);
    }

    public KcpClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
    }

    public synchronized void connect() {
        if (session != null) {
            return;
        }
        try {
            InetSocketAddress remote = parseRemote(serverUrl);
            ChannelConfig config = new ChannelConfig();
            config.setMtu(1400);
            config.setTimeoutMillis(60_000L);
            config.nodelay(true, 1, 2, true);
            eventLoopGroup = new NioEventLoopGroup(1);
            config.setNettyBootstrapGroup(eventLoopGroup, NioDatagramChannel.class);

            kcpBaseClient = new kcp.KcpClient(config);
            registerFuture = new CompletableFuture<>();
            session = kcpBaseClient.connect(new InetSocketAddress("0.0.0.0", 0), remote, config,
                    new OAuthKcpListener());

            // 等待服务端确认（服务端会回 "registered:clientId"）
            registerFuture.get(REGISTER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("KCP 连接失败: {}", e.getMessage(), e);
            throw new RuntimeException("KCP 连接失败", e);
        }
    }

    public synchronized void disconnect() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
            eventLoopGroup = null;
        }
    }

    public boolean isConnected() {
        return session != null && session.isActive();
    }

    public String getClientId() {
        return clientId;
    }

    public void send(String topic, Object message) {
        publish(topic, message);
    }

    public void publish(String topic, Object message) {
        checkConnected();
        writeRaw(topic + ":" + message);
    }

    /**
     * 同步请求-响应。
     */
    public String execute(String topic, Object message) {
        return execute(topic, message, DEFAULT_TIMEOUT_MS);
    }

    public String execute(String topic, Object message, long timeoutMs) {
        try {
            return executeAsync(topic, message, timeoutMs).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("KCP 请求执行失败: " + topic, e);
        }
    }

    public CompletableFuture<String> executeAsync(String topic, Object message) {
        return executeAsync(topic, message, DEFAULT_TIMEOUT_MS);
    }

    public CompletableFuture<String> executeAsync(String topic, Object message, long timeoutMs) {
        checkConnected();
        String requestId = java.util.UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        if (timeoutMs > 0) {
            future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        }
        future.whenComplete((result, error) -> pendingRequests.remove(requestId));
        writeRaw(REQ_PREFIX + topic + ":" + requestId + SEPARATOR + message);
        return future;
    }

    public KcpClient subscribe(String topic, BiConsumer<String, String> handler) {
        topicSubscribers.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    public void unsubscribe(String topic) {
        topicSubscribers.remove(topic);
    }

    public KcpClient onMessage(BiConsumer<String, String> listener) {
        messageListeners.add(listener);
        return this;
    }

    public KcpClient onConnect(Consumer<String> listener) {
        connectListeners.add(listener);
        return this;
    }

    public KcpClient onDisconnect(Consumer<String> listener) {
        disconnectListeners.add(listener);
        return this;
    }

    public KcpClient onError(Consumer<Throwable> listener) {
        errorListeners.add(listener);
        return this;
    }

    public KcpClient registerBean(Object bean) {
        if (bean == null) {
            return this;
        }
        Class<?> clazz = bean.getClass();
        annotatedBeans.put(clazz, bean);
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
                    topic = "#";
                }
                onMessageMethods.add(new AnnotatedMessage(clazz, method, topic));
            }
            if (method.isAnnotationPresent(OnError.class)) {
                onErrorMethods.add(new AnnotatedMethod(clazz, method));
            }
        }
        return this;
    }

    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public void close() {
        disconnect();
    }

    private void writeRaw(String text) {
        ByteBuf buf = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
        try {
            session.write(buf);
        } finally {
            // kcp-base 内部 retain/release
        }
    }

    private void checkConnected() {
        if (session == null || !session.isActive()) {
            throw new IllegalStateException("KCP 未连接");
        }
    }

    private InetSocketAddress parseRemote(String url) {
        // 解析 kcp://host:port
        String s = url;
        if (s.startsWith("kcp://")) {
            s = s.substring(6);
        }
        int idx = s.lastIndexOf(':');
        String host = idx >= 0 ? s.substring(0, idx) : s;
        int port = idx >= 0 ? Integer.parseInt(s.substring(idx + 1)) : DEFAULT_PORT;
        return new InetSocketAddress(host, port);
    }

    /**
     * 分发无参/带参注解方法（OnOpen/OnClose/OnError）。
     *
     * @param methods 注解方法列表
     * @param args    方法参数（可为空）
     */
    private void dispatchAnnotatedMethods(List<AnnotatedMethod> methods, Object... args) {
        for (AnnotatedMethod entry : methods) {
            Object bean = annotatedBeans.get(entry.beanClass());
            if (bean != null) {
                safeInvoke(bean, entry.method(), args);
            }
        }
    }

    /**
     * 按主题分发 OnMessage 注解方法。
     *
     * @param topic   主题
     * @param payload 消息内容
     */
    private void dispatchAnnotatedPublish(String topic, String payload) {
        for (AnnotatedMessage entry : onMessageMethods) {
            if (matchTopic(entry.topic(), topic)) {
                Object bean = annotatedBeans.get(entry.beanClass());
                if (bean != null) {
                    safeInvoke(bean, entry.method(), payload);
                }
            }
        }
    }

    /**
     * 主题匹配：支持 # 多级通配、+ / * 单级通配。
     *
     * @param pattern 订阅模式
     * @param topic   实际主题
     * @return true 表示匹配
     */
    private boolean matchTopic(String pattern, String topic) {
        if ("#".equals(pattern)) {
            return true;
        }
        String[] pp = pattern.split("/");
        String[] tp = topic.split("/");
        int p = 0;
        int t = 0;
        while (p < pp.length && t < tp.length) {
            if ("#".equals(pp[p])) {
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
     * 通知错误监听器。
     *
     * @param ex 异常
     */
    private void notifyError(Throwable ex) {
        for (Consumer<Throwable> l : errorListeners) {
            try {
                l.accept(ex);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 安全调用注解方法，异常统一走错误监听器。
     *
     * @param bean   目标实例
     * @param method 方法
     * @param args   参数
     */
    private void safeInvoke(Object bean, Method method, Object... args) {
        try {
            if (args == null || args.length == 0) {
                method.invoke(bean);
            } else {
                method.invoke(bean, args);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("KCP 客户端注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), cause);
            notifyError(cause);
            dispatchAnnotatedMethods(onErrorMethods, cause);
        } catch (Exception e) {
            log.error("KCP 客户端注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), e);
            notifyError(e);
            dispatchAnnotatedMethods(onErrorMethods, e);
        }
    }

    /**
     * 注解方法元信息。
     *
     * @param beanClass Bean 类型
     * @param method    注解方法
     */
    private record AnnotatedMethod(Class<?> beanClass, Method method) {
    }

    /**
     * OnMessage 注解方法元信息。
     *
     * @param beanClass Bean 类型
     * @param method    注解方法
     * @param topic     订阅主题
     */
    private record AnnotatedMessage(Class<?> beanClass, Method method, String topic) {
    }

    /**
     * kcp-base 客户端 KcpListener，把服务端消息分发到订阅者/监听器。
     */
    private final class OAuthKcpListener implements KcpListener {

        @Override
        public void onConnected(Ukcp ukcp) {
            // 服务端会收到 register:clientId 的第一帧作为注册请求
            // 这里仅触发连接事件，回写 register: 让服务端把 clientId 绑定
            sendRegister();
            notifyConnect(clientId);
            dispatchAnnotatedMethods(onOpenMethods);
        }

        @Override
        public void handleReceive(ByteBuf byteBuf, Ukcp ukcp) {
            // kcp-base 1.6.2 ReadTask 自行管理 ByteBuf 引用计数，此处不可 release（双重释放会 IllegalReferenceCountException）
            String line = byteBuf.toString(StandardCharsets.UTF_8).trim();
            if (line.isEmpty()) {
                return;
            }
            handleLine(line);
        }

        @Override
        public void handleException(Throwable ex, Ukcp ukcp) {
            log.error("KCP 客户端异常: {}", ex.getMessage(), ex);
            notifyError(ex);
            dispatchAnnotatedMethods(onErrorMethods, ex);
        }

        @Override
        public void handleClose(Ukcp ukcp) {
            notifyDisconnect(clientId);
            dispatchAnnotatedMethods(onCloseMethods);
        }

        private void sendRegister() {
            try {
                Thread.sleep(50);
                writeRaw(CMD_REGISTER + clientId);
            } catch (Exception e) {
                log.warn("注册消息发送失败: {}", e.getMessage());
            }
        }

        private void handleLine(String line) {
            int colon = line.indexOf(':');
            String topic = colon > 0 ? line.substring(0, colon) : line;
            String payload = colon > 0 ? line.substring(colon + 1) : "";

            // 注册确认：registered:clientId
            if (topic.equals(CMD_REGISTERED) || line.startsWith(CMD_REGISTERED + ":")) {
                if (registerFuture != null) {
                    registerFuture.complete(null);
                }
                return;
            }

            // 同步请求-响应：resp/<topic>:<requestId>|<body>
            if (topic.startsWith(RESP_PREFIX)) {
                String innerTopic = topic.substring(RESP_PREFIX.length());
                int sepIdx = payload.indexOf(SEPARATOR);
                String requestId = sepIdx >= 0 ? payload.substring(0, sepIdx) : null;
                String body = sepIdx >= 0 ? payload.substring(sepIdx + 1) : payload;
                if (requestId != null) {
                    CompletableFuture<String> future = pendingRequests.remove(requestId);
                    if (future != null) {
                        future.complete(body);
                        return;
                    }
                }
                // 没有匹配的 requestId，仍按普通消息分发
                dispatchMessage(innerTopic, body);
                return;
            }

            dispatchMessage(topic, payload);
        }

        private void dispatchMessage(String topic, String payload) {
            for (Map.Entry<String, List<BiConsumer<String, String>>> entry : topicSubscribers.entrySet()) {
                if (matchTopic(entry.getKey(), topic)) {
                    for (BiConsumer<String, String> handler : entry.getValue()) {
                        try {
                            handler.accept(topic, payload);
                        } catch (Exception e) {
                            log.error("KCP 订阅处理异常", e);
                            notifyError(e);
                        }
                    }
                }
            }
            for (BiConsumer<String, String> listener : messageListeners) {
                try {
                    listener.accept(topic, payload);
                } catch (Exception e) {
                    log.error("KCP 消息监听异常", e);
                }
            }
            // @OnMessage 注解分发
            dispatchAnnotatedPublish(topic, payload);
        }

        private boolean matchTopic(String pattern, String topic) {
            if ("#".equals(pattern)) {
                return true;
            }
            String[] pp = pattern.split("/");
            String[] tp = topic.split("/");
            int p = 0;
            int t = 0;
            while (p < pp.length && t < tp.length) {
                if ("#".equals(pp[p])) {
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

        private void notifyConnect(String clientId) {
            for (Consumer<String> l : connectListeners) {
                try {
                    l.accept(clientId);
                } catch (Exception ignored) {
                }
            }
        }

        private void notifyDisconnect(String clientId) {
            for (Consumer<String> l : disconnectListeners) {
                try {
                    l.accept(clientId);
                } catch (Exception ignored) {
                }
            }
        }

        private void notifyError(Throwable ex) {
            for (Consumer<Throwable> l : errorListeners) {
                try {
                    l.accept(ex);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
