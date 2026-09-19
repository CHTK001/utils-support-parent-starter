package com.chua.kcp.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.server.filter.DefaultServerFilterChain;
import com.chua.common.support.network.server.request.AbstractServerRequest;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.AbstractServerResponse;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import kcp.ChannelConfig;
import kcp.KcpListener;
import kcp.Ukcp;
import kcp.User;
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
 * 基于 kcp-基础 的 KCP 消息服务器实现（消息型，协议分类与 MQTT 一致）。
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
@Spi("kcp")
@Slf4j
public class KcpServer extends AbstractServer {

    /**
     * KCP 会话标识（conv），两端保持一致
     */
    public static final int KCP_CONV = 0x4b4350;

    /**
     * KCP 最大传输单元（字节）
     */
    /**
     * KCP MTU（最大传输单元）
     * <p>KCP 按 MTU 分包发送：MTU 越小单条消息分包越多、ACK 次数越多、吞吐越低。
     * 512 → 1400（udp 承载上限内）减少分包与确认开销，是 tcp/udp 吞吐差距的重要来源之一。</p>
     */
    private static final int KCP_MTU = 1400;

    /**
     * KCP 更新间隔（毫秒）
     * <p>KCP interval 决定发送 flush 频率：interval 越大每批数据等待越久、吞吐越低。
     * 默认 20ms 严重限制下行吞吐（实测约 1.3k ops/s vs tcp 5万+），
     * 降为 1ms 最大化发送频率（20→5ms +63%，5→2ms +18%，2→1ms 再提升）；
     * 配合 nodelay 快速模式（关拥塞控制+快速重传）接近 udp/tcp 量级。</p>
     */
    private static final int KCP_INTERVAL = 1;

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
     * 客户端连接表（客户端id -> ukcp）
     */
    private final Map<String, Ukcp> sessions = new HashMap<>();

    /**
     * 批量发送队列（连接 -> 待发送消息字节，无锁队列）
     * <p>publish/send 只入队，由批量 flusher 合并多条消息为一个 KCP 包写出，
     * 显著减少逐条 写入 与 ACK 确认次数（KCP 可靠确认是下行吞吐主瓶颈）。</p>
     */
    private final Map<Ukcp, java.util.concurrent.ConcurrentLinkedQueue<byte[]>> batchQueues =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 批量 flusher 列表（虚拟线程，按连接 哈希 分片并发冲刷）。
     */
    private volatile List<Thread> batchFlushers = new ArrayList<>();

    /**
     * 批量 flusher 生命周期标志。
     * <p>不能复用 {@code running}（AbstractServer.start() 先 doStart 后置 running=true，
     * 执行启动 内启动的 flusher 会因 running=false 立即退出导致批量队列永不冲刷）。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean batchRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 客户端元数据集合（客户端id -> 映射）
     */
    private final Map<String, Map<String, Object>> sessionMetadata = new HashMap<>();

    /**
     * 同步事件监听器列表
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 主题订阅处理器集合（topic -> 处理器 列表）
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
     * on打开 注解方法列表
     */
    private final List<AnnotatedMethod> onOpenMethods = new CopyOnWriteArrayList<>();

    /**
     * on关闭 注解方法列表
     */
    private final List<AnnotatedMethod> onCloseMethods = new CopyOnWriteArrayList<>();

    /**
     * on消息 注解方法列表
     */
    private final List<AnnotatedMessage> onMessageMethods = new CopyOnWriteArrayList<>();

    /**
     * on错误 注解方法列表
     */
    private final List<AnnotatedMethod> onErrorMethods = new CopyOnWriteArrayList<>();

    /**
     * Netty 事件循环组（kcp-基础 复用）
     */
    private EventLoopGroup eventLoopGroup;

    /**
     * 虚拟线程执行器（响应式 IO 回调）
     */
    private java.util.concurrent.ExecutorService virtualExecutor;

    /**
     * kcp-基础 服务器实例
     */
    private kcp.KcpServer kcpBaseServer;

    /**
     * KCP 配置（绑定到 kcp-基础 通道配置）
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
    /** 获取协议 */
    public String getProtocol() {
        return "kcp";
    }

    @Override
    /** 获取协议类型 */
    public ProtocolType getProtocolType() {
        return ProtocolType.KCP;
    }

    @Override
    /** 执行开始 */
    protected void doStart() {
        setting.setProtocol("kcp");
        channelConfig = new ChannelConfig();
        channelConfig.setMtu(KCP_MTU);
        channelConfig.setTimeoutMillis(60_000L);
        channelConfig.nodelay(true, KCP_INTERVAL, KCP_FAST_RESEND, true);

        // 响应式:虚拟线程执行器 + boss 线程数(bossCore),Netty 4.2 MultiThreadIoEventLoopGroup
 // 虚拟线程处理 IO 就绪回调,天然适配高并发低阻塞;bossthreads 控制并发处理连接数
        virtualExecutor = ThreadUtils.newVirtualThreadPerTaskExecutor();
        eventLoopGroup = new io.netty.channel.MultiThreadIoEventLoopGroup(
                setting.getBossThreads(),
                virtualExecutor,
                io.netty.channel.nio.NioIoHandler.newFactory());
        channelConfig.setNettyBootstrapGroup(eventLoopGroup, NioDatagramChannel.class);

        kcpBaseServer = kcp.KcpServer.createStarted(channelConfig, new OAuthKcpListener(), setting.getPort());
 // 启动批量 flusher 多实例：按连接 哈希 分片，多个虚拟线程并发合并写出，
 // 避免单 flusher 串行瓶颈（无锁 并发链接队列 入队，无锁并发安全）
        int flusherCount = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        batchFlushers = new ArrayList<>(flusherCount);
        batchRunning.set(true);
        for (int i = 0; i < flusherCount; i++) {
            final int shard = i;
            Thread flusher = Thread.ofVirtual().name("kcp-batch-flusher-" + shard).start(() -> {
                while (batchRunning.get()) {
                    try {
                        Thread.sleep(KCP_INTERVAL);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    flushBatches(shard, flusherCount);
                }
            });
            batchFlushers.add(flusher);
        }
        log.info("KCP 服务器启动: {}:{} (响应式, bossCore={}, 虚拟线程, flushers={})",
                setting.getHost(), setting.getPort(), setting.getBossThreads(), flusherCount);
    }

    /**
     * 批量冲刷（分片）：只处理本分片内的连接，多个 flusher 并发执行。
     * <p>合并多条消息为一次 {@code ukcp.write}，减少 KCP 包数 → 减少 ACK 确认次数，
     * 直击"可靠确认是下行吞吐主瓶颈"。</p>
     *
     * @param shard 当前分片号
     * @param total 分片总数
     */
    private void flushBatches(int shard, int total) {
        int idx = 0;
        for (Map.Entry<Ukcp, java.util.concurrent.ConcurrentLinkedQueue<byte[]>> entry : batchQueues.entrySet()) {
 // 按连接 哈希 分片，各 flusher 只冲刷自己的连接，无锁并发不冲突
            if ((idx++ & 0x7fffffff) % total != shard) {
                continue;
            }
            java.util.concurrent.ConcurrentLinkedQueue<byte[]> queue = entry.getValue();
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            Ukcp ukcp = entry.getKey();
            if (ukcp == null || !ukcp.isActive()) {
                continue;
            }
            java.io.ByteArrayOutputStream merged = new java.io.ByteArrayOutputStream(256);
            byte[] msg;
            while ((msg = queue.poll()) != null) {
                merged.write(msg, 0, msg.length);
                merged.write('\n');
            }
            ByteBuf buf = Unpooled.wrappedBuffer(merged.toByteArray());
            try {
                ukcp.write(buf);
            } catch (Exception e) {
                log.debug("KCP 批量发送异常: {}", e.getMessage());
            }
        }
    }

    @Override
    /** 执行停止 */
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
 // 编码一次，各连接共享字节数组（wrapped缓冲 零拷贝视图），避免逐连接重复编码
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (Ukcp ukcp : sessions.values()) {
            sendTo(ukcp, bytes);
        }
        notifyListeners(listener -> listener.onMessage("broadcast", topic, String.valueOf(message)));
    }

    /**
     * 批量发送：消息入队（无锁），由批量 flusher 合并为一个 KCP 包写出。
     *
     * @param ukcp  连接
     * @param bytes 消息字节
     */
    private void sendTo(Ukcp ukcp, byte[] bytes) {
        batchQueues.computeIfAbsent(ukcp, k -> new java.util.concurrent.ConcurrentLinkedQueue<>()).offer(bytes);
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
    /**
     * 发送转为
     *
     * @param ukcp ukcp
     * @param text 文本
     */
    private void sendTo(Ukcp ukcp, String text) {
        // 统一以 \n 结尾：客户端按行切分（批量聚合后跨包缓冲依赖 \n 边界），
 // 注册确认 注册:xxx 也必须带 \n，否则客户端永远等不到 注册
        ByteBuf buf = Unpooled.copiedBuffer(text + "\n", StandardCharsets.UTF_8);
        try {
            ukcp.write(buf);
        } finally {
 // kcp-基础 会在内部 retain/release，调用方不再持有
        }
    }

    /**
     * 获取当前所有已连接的客户端标识列表。
     * @return 获取连接客户端的结果
     */
    public List<String> getConnectedClients() {
        return new ArrayList<>(sessions.keySet());
    }

    /**
     * 获取指定客户端的元数据。
     * @param clientId 客户端标识
     * @return 获取客户端metadata的结果
     */
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = sessionMetadata.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    /**
     * 注册主题订阅处理器。
     * @param topic topic
     * @param handler 处理器
     * @return on订阅的结果
     */
    public KcpServer onSubscribe(String topic, BiConsumer<String, String> handler) {
        topicSubscribers.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 添加监听器
     *
     * @param listener 监听器
     */
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除监听器
     *
     * @param listener 监听器
     */
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    /**
     * On连接
     *
     * @param listener 监听器
     */
    public void onConnect(Consumer<String> listener) {
        connectListeners.add(listener);
    }

    /**
     * On断开
     *
     * @param listener 监听器
     */
    public void onDisconnect(Consumer<String> listener) {
        disconnectListeners.add(listener);
    }

    /**
     * On记录错误
     *
     * @param listener 监听器
     */
    public void onError(Consumer<Throwable> listener) {
        errorListeners.add(listener);
    }

    @Override
    /** 注册Bean */
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
    * 通知监听器
    *
    * @param action 动作
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
     * 通知连接监听器
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
     * 通知断开监听器
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
     * 通知记录错误监听器
     *
     * @param cause cause
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
     * 分发annotated方法
     *
     * @param methods 方法
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
     * 分发Annotated发布
     *
     * @param topic topic
     * @param payload payload
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
     * Safe调用
     *
     * @param bean Bean
     * @param method 方法
     * @param args 参数
     */
    private void safeInvoke(Object bean, Method method, Object... args) {
        try {
            ReflectUtils.invoke(bean, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
        } catch (Exception e) {
            log.error("KCP 注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), e);
            notifyErrorListeners(e);
        }
    }

    /**
     * 匹配topic
     *
     * @param pattern 模式
     * @param topic topic
     * @return 匹配topic的结果
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
     * kcp-基础 的 kcp监听器 实现，把会话与 OAuth 业务绑定。
     * @author CH
     * @since 4.0.0
     */
    private final class OAuthKcpListener implements KcpListener {

        @Override
        /** on连接 */
        public void onConnected(Ukcp ukcp) {
            // kcp-base 1.6.2 的 Ukcp 未暴露 remoteAddress()，用 hashCode 兜底，由客户端 register: 重命名
            String initialId = "client-" + System.nanoTime() + "-" + ukcp.hashCode();
            sessions.put(initialId, ukcp);
            Map<String, Object> meta = new HashMap<>();
            meta.put("clientId", initialId);
            sessionMetadata.put(initialId, meta);
 // 用 kcp-基础 用户 缓存 客户端标识（不持有 通道，避免外部 通道 失效）
            kcp.User user = ukcp.user();
            if (user != null) {
                user.setCache(initialId);
            }
            notifyListeners(listener -> listener.onClientConnected(initialId, meta));
            notifyConnectListeners(initialId);
            dispatchAnnotatedMethods(onOpenMethods);
 // 连接建立后主动回 注册:initialid，让客户端的 注册期货 完成
 // 注意：KCP 是顺序字节流，发送转为 内部会 缓冲 直到 conv 建立完成；
 // 在 on连接 阶段写一帧 注册: 让客户端收到后能完成 注册 握手。
            try {
                sendTo(ukcp, "registered:" + initialId);
            } catch (Exception e) {
                log.warn("KCP 发送 registered 失败: {}", e.getMessage());
            }
        }

        @Override
        /** 处理接收 */
        public void handleReceive(ByteBuf byteBuf, Ukcp ukcp) {
 // kcp-基础 1.6.2 读取任务 自行管理 bytebuf 引用计数，此处不可 release（双重释放会 illegal引用数量异常）
            String line = byteBuf.toString(StandardCharsets.UTF_8).trim();
            handleLine(ukcp, line);
        }

        @Override
        /** 处理异常 */
        public void handleException(Throwable ex, Ukcp ukcp) {
            log.error("KCP 连接处理异常: {}", ex.getMessage(), ex);
            notifyErrorListeners(ex);
        }

        @Override
        /** 处理关闭 */
        public void handleClose(Ukcp ukcp) {
            kcp.User user = ukcp.user();
            String clientId = user != null && user.getCache() instanceof String
                    ? (String) user.getCache() : null;
            if (clientId != null) {
                sessions.remove(clientId);
                sessionMetadata.remove(clientId);
                notifyListeners(listener -> listener.onClientDisconnected(clientId));
                notifyDisconnectListeners(clientId);
                dispatchAnnotatedMethods(onCloseMethods);
            }
        }

        /**
         * 处理线
         *
         * @param ukcp ukcp
         * @param line 线
         */
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
            // 业务消息异步派发到虚拟线程处理，避免 IO 线程同步阻塞(登录等业务含 DB/Redis 查询)
            String fClientId = clientId;
            String fTopic = topic;
            String fPayload = payload;
            virtualExecutor.execute(() -> dispatchMessage(fClientId, fTopic, fPayload));
        }

        /**
         * 处理注册
         *
         * @param ukcp ukcp
         * @param clientId 客户端标识
         */
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

        /**
         * 分发消息
         *
         * @param clientId 客户端标识
         * @param topic topic
         * @param payload payload
         */
        private void dispatchMessage(String clientId, String topic, String payload) {
 // 消息链路接入 服务端过滤器 体系：构造协议无关的 请求/响应，走统一过滤器链，
            // 链尾执行订阅分发/注解分发/监听器通知
            KcpServerRequest request = new KcpServerRequest(clientId, topic, payload);
            KcpServerResponse response = new KcpServerResponse();
            DefaultServerFilterChain chain = new DefaultServerFilterChain(
                    filterManager.getMergedFilters(), (req, res) -> {
                if (res.isEnded()) {
                    return;
                }
                dispatchToHandlers(clientId, topic, payload);
            });
            try {
                chain.doFilter(request, response);
            } catch (Exception e) {
                log.error("KCP 过滤器链执行异常: {}", e.getMessage(), e);
                notifyErrorListeners(e);
            }
        }

        /**
         * 分发消息到订阅者、注解方法与监听器（过滤器 链尾业务逻辑）。
         * @param clientId 客户端标识
         * @param topic topic
         * @param payload payload
         */
        private void dispatchToHandlers(String clientId, String topic, String payload) {
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

    /**
     * KCP 消息的协议无关请求视图（topic → 路径，payload → 主体，客户端id → 远程）。
     */
    private static final class KcpServerRequest extends AbstractServerRequest {

        /**
         * 客户端标识
         */
        private final String clientId;
        /**
         * 消息主题
         */
        private final String topic;
        /**
         * 消息载荷字节数组
         */
        private final byte[] payload;

        KcpServerRequest(String clientId, String topic, String payload) {
            this.clientId = clientId;
            this.topic = topic;
            this.payload = payload.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        /** 获取头部 */
        public HttpHeader getHeaders() {
            return HttpHeader.create();
        }

        @Override
        /** 获取头部 */
        public String getHeader(String name) {
            return null;
        }

        @Override
        /** 获取Uri */
        public String getUri() {
            return topic;
        }

        @Override
        /** 获取路径 */
        public String getPath() {
            return topic;
        }

        @Override
        /** 获取方法 */
        public HttpMethod getMethod() {
            return HttpMethod.POST;
        }

        @Override
        /** 获取远程地址 */
        public String getRemoteAddress() {
            return clientId;
        }

        @Override
        /** 获取远程端口 */
        public int getRemotePort() {
            return 0;
        }

        @Override
        /** 读取主体 */
        protected byte[] readBody() {
            return payload;
        }
    }

    /**
     * KCP 消息的协议无关响应视图（KCP 无 HTTP 响应体，仅用于 过滤器 链语义）。
     */
    private static final class KcpServerResponse extends AbstractServerResponse {

        @Override
        /** 获取输出流 */
        public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        /** 写入Raw */
        public void writeRaw(byte[] bytes) {
            // KCP 文本协议无原始响应写回，忽略
        }
    }

    /**
     * annotated方法
     *
     * @param beanClass Bean类
     * @param method 方法
     * @return annotated方法的结果
     */
    private record AnnotatedMethod(Class<?> beanClass, Method method) {
    }

    /**
     * annotated消息
     *
     * @param beanClass Bean类
     * @param method 方法
     * @param topic topic
     * @return annotated消息的结果
     */
    private record AnnotatedMessage(Class<?> beanClass, Method method, String topic) {
    }

    /**
     * 用 并发哈希映射 包装 topic->处理器（避免再写一个类）。
     */
    private static final class ConcurrentHashMapAliasMap extends java.util.concurrent.ConcurrentHashMap<String, List<BiConsumer<String, String>>> {
    }
}
