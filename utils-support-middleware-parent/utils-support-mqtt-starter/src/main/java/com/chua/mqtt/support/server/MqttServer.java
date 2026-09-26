package com.chua.mqtt.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.network.protocol.MqttTopics;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * MQTT 嵌入式服务器，轻量级实现。
 * <p>
 * 继承 {@link AbstractServer}，支持 {@link OnOpen}/{@link OnClose}/{@link OnMessage}/{@link OnError} 注解处理。
 * 基于原生 服务端Socket 实现 MQTT 3.1.1 协议的 CONNECT/CONNACK/PUBLISH/PUBACK/PUBREC/PUBREL/PUBCOMP/
 * SUBSCRIBE/SUBACK/UNSUBSCRIBE/UNSUBACK/PINGREQ/PINGRESP/DISCONNECT 数据包。
 * </p>
 *
 * <h2>投递语义</h2>
 * <ul>
 *   <li>入站 QoS 2 完整握手：PUBLISH → PUBREC → PUBREL → 投递 → PUBCOMP</li>
 *   <li>出站最大 QoS 为 {@link #setMaxSupportedQos(int)}（默认 1），SUBACK 按授予 QoS 回包，
 *   转发时按 {@code min(发布 QoS, 授予 QoS)} 降级</li>
 *   <li>不支持保留消息（RETAIN 标志被忽略）与共享订阅（{@code $share/}）</li>
 *   <li>异常断开（未收到 DISCONNECT 即断链）时按 CONNECT 的 Will 标志发布遗嘱消息</li>
 * </ul>
 *
 * <h2>安全</h2>
 * <p>默认接受任意 CONNECT。生产部署应通过 {@link #withAuthenticator(MqttAuthenticator)} 校验凭据，
 * 并在 {@link ServerSetting#getHost()} 非回环地址时于启动日志给出告警。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * ServerSetting setting = ServerSetting.defaults();
 * setting.setPort(1883);
 * MqttServer server = new MqttServer(setting);
 *
 * server.register(new Object() {
 *     &#64;OnOpen
 *     public void onConnect() { System.out.println("客户端连接"); }
 *
 *     &#64;OnMessage("order/#")
 *     public void onOrder(String payload) { System.out.println("收到: " + payload); }
 *
 *     &#64;OnClose
 *     public void onDisconnect() { System.out.println("客户端断开"); }
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
@Spi("mqtt")
public class MqttServer extends AbstractServer {

    /**
     * 协议级 CONNACK 返回码：接受连接
     */
    private static final int ACCEPTED = 0x00;
    /**
     * 协议级 CONNACK 返回码：不可接受的协议名/版本
     */
    private static final int UNACCEPTABLE_PROTOCOL = 0x01;
    /**
     * 协议级 CONNACK 返回码：标识符被拒绝
     */
    private static final int IDENTIFIER_REJECTED = 0x02;
    /**
     * 协议级 CONNACK 返回码：用户名或密码错误
     */
    private static final int BAD_USERNAME_OR_PASSWORD = 0x04;

    /**
     * 原生 MQTT 主题订阅处理器（保留原有 topicsubscribers 机制）
     */
    private final Map<String, List<BiConsumer<String, String>>> topicSubscribers = new ConcurrentHashMap<>();

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
     * 客户端连接管理
     */
    private final Map<String, ClientSession> clients = new ConcurrentHashMap<>();

    /**
     * 会话序号，用于生成未提供 clientId 时的自动标识
     */
    private final AtomicInteger sessionSeq = new AtomicInteger();

    /**
     * 凭据校验器，为空时接受任意连接
     */
    private volatile MqttAuthenticator authenticator;

    /**
     * 服务端支持的最大出站 QoS
     */
    private volatile int maxSupportedQos = 1;

    /**
     * 接收连接线程池
     */
    private ExecutorService bossPool;

    /**
     * 处理消息线程池
     */
    private ExecutorService workerPool;

    /**
     * 服务端Socket 实例
     */
    private ServerSocket serverSocket;

    /**
     * 创建 mqtt服务端 实例
     * @param setting setting
     */
    public MqttServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /**
     * 执行开始
    */
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(setting.isSoReuseAddr());
            serverSocket.setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384));
            serverSocket.bind(addr, Math.max(setting.getBacklog(), 2048));
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(serverSocket.getLocalPort());
            bossPool = ThreadUtils.newVirtualThreadPerTaskExecutor();
            workerPool = ThreadUtils.newVirtualThreadPerTaskExecutor();
            running = true;

            bossPool.submit(this::acceptLoop);
            log.info("MQTT 服务器启动: {}:{} (backlog={}, maxQos={}, virtualThreads=true)",
                    setting.getHost(), setting.getPort(), Math.max(setting.getBacklog(), 2048), maxSupportedQos);
            warnIfInsecure();
        } catch (IOException e) {
            running = false;
            throw new RuntimeException("MQTT 服务器启动失败", e);
        }
    }

    /**
     * 绑定非回环地址且无凭据校验器时输出告警。
     */
    private void warnIfInsecure() {
        if (authenticator != null) {
            return;
        }
        String host = setting.getHost();
        boolean loopback = host == null || host.isEmpty()
                || "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
        if (!loopback) {
            log.warn("MQTT 服务器监听 {}:{} 且未配置 MqttAuthenticator，任意客户端均可连接并发布消息", host, setting.getPort());
        }
    }

    @Override
    /**
     * 执行停止
    */
    protected void doStop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (bossPool != null) {
            bossPool.shutdownNow();
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
        for (ClientSession session : clients.values()) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
        topicSubscribers.clear();
        log.info("MQTT 服务器停止");
    }

    @Override
    /**
     * 获取协议类型
    */
    public ProtocolType getProtocolType() {
        return ProtocolType.MQTT;
    }

    /**
     * 设置凭据校验器；为 {@code null} 时接受任意 CONNECT。
     *
     * @param authenticator 校验器
     * @return 当前服务器实例
     */
    public MqttServer withAuthenticator(MqttAuthenticator authenticator) {
        this.authenticator = authenticator;
        return this;
    }

    /**
     * 设置服务端支持的最大出站 QoS（0~1）。
     *
     * @param maxSupportedQos 最大 QoS
     * @return 当前服务器实例
     */
    public MqttServer setMaxSupportedQos(int maxSupportedQos) {
        this.maxSupportedQos = Math.max(0, Math.min(1, maxSupportedQos));
        return this;
    }

    /**
     * 当前在线会话数。
     *
     * @return 会话数
     */
    public int getSessionCount() {
        return clients.size();
    }

    /**
     * 注册主题订阅处理器。
     *
     * @param topic   主题名称，支持通配符 # 和 +
     * @param handler 消息处理器
     * @return 当前服务器实例，支持链式调用
     */
    public MqttServer onSubscribe(String topic, BiConsumer<String, String> handler) {
        if (!MqttTopics.isValidFilter(topic)) {
            throw new IllegalArgumentException("非法的 MQTT 主题过滤器: " + topic);
        }
        topicSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 服务端主动发布：投递给本地处理器与所有匹配的在线订阅者。
     *
     * @param topic   主题名称
     * @param payload 消息内容
     */
    public void publish(String topic, String payload) {
        publish(topic, payload, 0);
    }

    /**
     * 服务端主动发布。
     *
     * @param topic   主题名称
     * @param payload 消息内容
     * @param qos     发布 QoS（出站仍受 {@link #setMaxSupportedQos(int)} 上限约束）
     */
    public void publish(String topic, String payload, int qos) {
        if (!MqttTopics.isValidTopic(topic)) {
            throw new IllegalArgumentException("MQTT 发布主题不得包含通配符: " + topic);
        }
        notifyLocalHandlers(topic, payload);
        dispatchAnnotatedPublish(topic, payload);
        broadcast(topic, payload == null ? "" : payload, qos, null);
    }

    /**
     * 遍历原生主题处理器。
     *
     * @param topic   实际主题
     * @param payload 消息内容
     */
    private void notifyLocalHandlers(String topic, String payload) {
        for (Map.Entry<String, List<BiConsumer<String, String>>> entry : topicSubscribers.entrySet()) {
            if (!MqttTopics.matches(entry.getKey(), topic)) {
                continue;
            }
            for (BiConsumer<String, String> handler : entry.getValue()) {
                try {
                    handler.accept(topic, payload);
                } catch (Exception e) {
                    log.error("MQTT 推送异常", e);
                    notifyError(e);
                }
            }
        }
    }

    /**
     * 广播给在线订阅者，排除发起方自身。
     *
     * @param topic   主题
     * @param message 消息
     * @param qos     发布 QoS
     * @param sender  发起方会话，服务端发布时为 {@code null}
     */
    private void broadcast(String topic, String message, int qos, ClientSession sender) {
        for (ClientSession other : clients.values()) {
            if (other == sender) {
                continue;
            }
            int granted = other.grantedQos(topic);
            if (granted < 0) {
                continue;
            }
            try {
                other.sendPublish(topic, message, MqttTopics.effectiveQos(qos, granted, maxSupportedQos));
            } catch (IOException e) {
                log.debug("MQTT 转发消息失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 派发错误监听器与 {@code @OnError} 方法。
     *
     * @param e 异常
     */
    private void notifyError(Throwable e) {
        for (Consumer<Throwable> listener : errorListeners) {
            try {
                listener.accept(e);
            } catch (Exception ex) {
                log.error("MQTT 错误监听器执行异常", ex);
            }
        }
        dispatchAnnotatedMethods(onErrorMethods, e);
    }

    // ==================== 注解方法缓存 ====================

    /**
     * onopenmethods
    */
    private final List<AnnotatedMethod> onOpenMethods = new CopyOnWriteArrayList<>();
    /**
     * onclosemethods
    */
    private final List<AnnotatedMethod> onCloseMethods = new CopyOnWriteArrayList<>();
    /**
     * ON消息方法
    */
    private final List<AnnotatedMessage> onMessageMethods = new CopyOnWriteArrayList<>();
    /**
     * ON错误方法
    */
    private final List<AnnotatedMethod> onErrorMethods = new CopyOnWriteArrayList<>();

    @Override
    /**
     * 注册Bean
    */
    public MqttServer registerBean(Object handler) {
        super.registerBean(handler);
        if (handler == null) {
            return this;
        }
        Class<?> clazz = handler.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnOpen.class)) {
                onOpenMethods.add(new AnnotatedMethod(clazz, method));
            }
            if (method.isAnnotationPresent(OnClose.class)) {
                onCloseMethods.add(new AnnotatedMethod(clazz, method));
            }
            if (method.isAnnotationPresent(OnMessage.class)) {
                OnMessage ann = method.getAnnotation(OnMessage.class);
                String topic = ann.value();
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

    /**
     * annotated方法
     *
     * @param beanClass Bean类
     * @param method 方法
     * @return annotated方法的结果
     */
    private record AnnotatedMethod(Class<?> beanClass, Method method) {}
    /**
     * annotated消息
     *
     * @param beanClass Bean类
     * @param method 方法
     * @param topic topic
     * @return annotated消息的结果
     */
    private record AnnotatedMessage(Class<?> beanClass, Method method, String topic) {}

    /**
     * 分发annotated方法
     *
     * @param methods 方法
     * @param args 参数
     */
    private void dispatchAnnotatedMethods(List<AnnotatedMethod> methods, Object... args) {
        ObjectContext ctx = getObjectContext();
        if (ctx == null) {
            return;
        }
        for (AnnotatedMethod entry : methods) {
            Object bean = ctx.getBeanOfType(entry.beanClass());
            if (bean != null) {
                safeInvoke(bean, entry.method(), args);
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
        ObjectContext ctx = getObjectContext();
        if (ctx == null) {
            return;
        }
        for (AnnotatedMessage entry : onMessageMethods) {
            if (MqttTopics.matches(entry.topic(), topic)) {
                Object bean = ctx.getBeanOfType(entry.beanClass());
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
            log.error("MQTT 注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), e);
            notifyError(e);
        }
    }

    /**
     * accept循环
    */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (setting.getMaxConnections() > 0 && clients.size() >= setting.getMaxConnections()) {
                    log.warn("MQTT 连接数已达上限 {}，拒绝来自 {} 的连接",
                            setting.getMaxConnections(), socket.getRemoteSocketAddress());
                    closeQuietly(socket);
                    continue;
                }
                socket.setTcpNoDelay(setting.isTcpNoDelay());
                String sessionKey = "session-" + sessionSeq.incrementAndGet();
                ClientSession session = new ClientSession(sessionKey, socket);
                clients.put(sessionKey, session);
                workerPool.submit(session::handleLoop);
            } catch (IOException e) {
                if (running) {
                    log.error("MQTT 接受连接异常", e);
                    notifyError(e);
                }
            }
        }
    }

    /**
     * 静默关闭套接字。
     *
     * @param socket 套接字
     */
    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }

    // ==================== 客户端会话 ====================
    /**
     * 客户端会话类。
     *
     * <p>每个会话一个读取线程；所有写出都在 {@link #writeLock} 下进行，
     * 避免广播线程与会话线程交错写入同一个 {@link DataOutputStream}。</p>
     *
     * @author CH
     * @since 4.0.0
     */

    private class ClientSession {
        /**
         * 会话在 clients 映射中的稳定键，不随 CONNECT 上报的 clientId 变化
        */
        private final String sessionKey;
        /**
         * 客户端标识
        */
        private volatile String clientId;
        /**
         * Socket
        */
        private final Socket socket;
        /**
         * 入
        */
        private final DataInputStream in;
        /**
         * 出
        */
        private final DataOutputStream out;
        /**
         * 写出锁：保护 out 的帧完整性
        */
        private final Object writeLock = new Object();
        /**
         * Subscriptions：主题过滤器 -> 授予 QoS
        */
        private final Map<String, Integer> subscriptions = new ConcurrentHashMap<>();
        /**
         * 等待 PUBREL 的 QoS 2 消息
        */
        private final Map<Integer, PendingPublish> pendingQos2 = new ConcurrentHashMap<>();
        /**
         * 出站报文标识发生器（1~65535 循环）
        */
        private int nextPacketId;
        /**
         * 是否已完成 CONNECT 握手
        */
        private volatile boolean connected;
        /**
         * 是否收到 DISCONNECT（决定遗嘱是否发布）
        */
        private volatile boolean disconnectRequested;
        /**
         * 是否已关闭
        */
        private volatile boolean closed;
        /**
         * 遗嘱消息（CONNECT Will Flags 解析而来）
        */
        private volatile byte[] willMessage;
        /**
         * 遗嘱主题
        */
        private volatile String willTopic;
        /**
         * 遗嘱 QoS
        */
        private volatile int willQos;
        /**
         * keepAlive 秒数
        */
        private volatile int keepAliveSeconds;

        ClientSession(String sessionKey, Socket socket) throws IOException {
            this.sessionKey = sessionKey;
            this.clientId = sessionKey;
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        /**
         * 查询某主题的授予 QoS。
         *
         * @param topic 实际主题
         * @return 未订阅返回 -1
         */
        int grantedQos(String topic) {
            int best = -1;
            for (Map.Entry<String, Integer> entry : subscriptions.entrySet()) {
                if (MqttTopics.matches(entry.getKey(), topic)) {
                    best = Math.max(best, entry.getValue());
                }
            }
            return best;
        }

        void handleLoop() {
            try {
                while (running && !socket.isClosed()) {
                    int firstByte = in.readUnsignedByte();
                    int type = (firstByte >> 4) & 0x0F;
                    int flags = firstByte & 0x0F;
                    if (!connected && type != 1) {
                        throw new MqttProtocolViolation("首个数据包必须是 CONNECT，实际类型 " + type);
                    }
                    byte[] packet = readPacket();
                    switch (type) {
                        case 1 -> handleConnect(packet);
                        case 3 -> handlePublish(firstByte, packet);
                        case 4 -> {
                            // PUBACK：客户端确认服务端出站 QoS 1 消息，无持久化状态需变更
                        }
                        case 6 -> handlePubrel(flags, packet);
                        case 8 -> handleSubscribe(packet);
                        case 10 -> handleUnsubscribe(packet);
                        case 12 -> handlePingreq();
                        case 14 -> {
                            disconnectRequested = true;
                            return;
                        }
                        default -> throw new MqttProtocolViolation("不支持的数据包类型: " + type);
                    }
                }
            } catch (MqttProtocolViolation e) {
                log.warn("MQTT 协议违例 (clientId={}): {}", clientId, e.getMessage());
            } catch (EOFException | InterruptedIOException e) {
                log.debug("MQTT 客户端 {} 连接结束: {}", clientId, e.getMessage());
            } catch (IOException e) {
                if (running) {
                    log.debug("MQTT 客户端 {} 读取异常: {}", clientId, e.getMessage());
                }
                notifyError(e);
            } catch (RuntimeException e) {
                log.error("MQTT 会话处理异常 (clientId={})", clientId, e);
                notifyError(e);
            } finally {
                close();
            }
        }

        /**
         * 处理连接
         *
         * @param packet 可变头 + 载荷
        */
        private void handleConnect(byte[] packet) throws IOException {
            if (connected) {
                throw new MqttProtocolViolation("重复的 CONNECT 数据包");
            }
            PacketReader r = new PacketReader(packet);
            String protocolName = r.string("协议名");
            int level = r.unsignedByte("协议级别");
            int connectFlags = r.unsignedByte("连接标志");
            int keepAlive = r.unsignedShort("keepAlive");
            String reported = r.string("客户端标识");

            boolean cleanSession = (connectFlags & 0x02) != 0;
            boolean willFlag = (connectFlags & 0x04) != 0;
            int willQosValue = (connectFlags >> 3) & 0x03;
            boolean willRetain = (connectFlags & 0x20) != 0;
            boolean passwordFlag = (connectFlags & 0x40) != 0;
            boolean usernameFlag = (connectFlags & 0x80) != 0;

            String willTopicValue = null;
            byte[] willPayload = null;
            if (willFlag) {
                willTopicValue = r.string("遗嘱主题");
                willPayload = r.bytes("遗嘱消息");
            }
            String username = usernameFlag ? r.string("用户名") : null;
            byte[] password = passwordFlag ? r.bytes("密码") : null;

            if (!"MQTT".equals(protocolName) && !"MQIsdp".equals(protocolName)) {
                sendConnack(UNACCEPTABLE_PROTOCOL);
                throw new MqttProtocolViolation("不支持的协议名: " + protocolName);
            }
            if (level != 3 && level != 4) {
                sendConnack(UNACCEPTABLE_PROTOCOL);
                throw new MqttProtocolViolation("不支持的协议级别: " + level);
            }
            if (willQosValue == 3) {
                sendConnack(UNACCEPTABLE_PROTOCOL);
                throw new MqttProtocolViolation("遗嘱 QoS 保留了非法值 3");
            }
            String effectiveId = reported;
            if (effectiveId.isEmpty()) {
                if (!cleanSession) {
                    sendConnack(IDENTIFIER_REJECTED);
                    throw new MqttProtocolViolation("非清理会话要求提供 clientId");
                }
                effectiveId = clientId;
            }
            this.clientId = effectiveId;

            MqttAuthenticator verifier = authenticator;
            if (verifier != null
                    && !verifier.authenticate(effectiveId, username,
                    password == null ? null : new String(password, StandardCharsets.UTF_8))) {
                sendConnack(BAD_USERNAME_OR_PASSWORD);
                throw new MqttProtocolViolation("凭据校验失败");
            }

            this.keepAliveSeconds = keepAlive;
            if (keepAlive > 0) {
                // 读超时按 1.5 个 keepAlive 周期收口，PINGREQ 本身会刷新计时
                socket.setSoTimeout(keepAlive * 1500);
            }
            this.willTopic = willFlag ? willTopicValue : null;
            this.willMessage = willFlag ? willPayload : null;
            this.willQos = willQosValue;
            this.connected = true;

            log.debug("MQTT CONNECT: clientId={}, keepAlive={}, cleanSession={}, willRetain={}",
                    clientId, keepAlive, cleanSession, willRetain);
            sendConnack(ACCEPTED);

            for (Consumer<String> listener : connectListeners) {
                try {
                    listener.accept(clientId);
                } catch (Exception e) {
                    log.error("MQTT 连接回调异常", e);
                    notifyError(e);
                }
            }
            dispatchAnnotatedMethods(onOpenMethods);
        }

        /**
         * 发布遗嘱消息（异常断开时调用）。
         */
        private void publishWill() {
            byte[] payload = willMessage;
            String topic = willTopic;
            if (payload == null || topic == null) {
                return;
            }
            broadcast(topic, new String(payload, StandardCharsets.UTF_8), willQos, this);
            notifyLocalHandlers(topic, new String(payload, StandardCharsets.UTF_8));
        }

        /**
         * 处理发布
         *
         * @param firstByte 首字节（含 QoS/DUP/RETAIN 标志）
         * @param packet    报文内容
         */
        private void handlePublish(int firstByte, byte[] packet) throws IOException {
            int qos = (firstByte >> 1) & 0x03;
            boolean retain = (firstByte & 0x01) != 0;
            if (retain) {
                log.debug("MQTT 服务端不支持保留消息，RETAIN 标志已忽略: clientId={}", clientId);
            }
            PacketReader r = new PacketReader(packet);
            String topic = r.string("主题");
            if (topic.isEmpty()) {
                throw new MqttProtocolViolation("PUBLISH 主题为空");
            }
            int packetId = 0;
            if (qos > 0) {
                packetId = r.unsignedShort("报文标识符");
                if (packetId == 0) {
                    throw new MqttProtocolViolation("PUBLISH 报文标识符不能为 0");
                }
            }
            byte[] payloadBytes = r.tail();
            String message = new String(payloadBytes, StandardCharsets.UTF_8);

            log.debug("MQTT 收到: topic={}, qos={}, payload={}", topic, qos, message);

            if (qos == 1) {
                sendPuback(packetId);
                deliver(topic, message, qos);
            } else if (qos == 2) {
                // QoS 2：先回 PUBREC，收到 PUBREL 才投递，保证恰好一次
                pendingQos2.put(packetId, new PendingPublish(topic, message, qos));
                sendPubrec(packetId);
            } else {
                deliver(topic, message, qos);
            }
        }

        /**
         * 投递一条已确认收到的消息。
         *
         * @param topic   主题
         * @param message 消息
         * @param qos     发布 QoS
         */
        private void deliver(String topic, String message, int qos) {
            notifyLocalHandlers(topic, message);
            broadcast(topic, message, qos, this);
            dispatchAnnotatedPublish(topic, message);
        }

        /**
         * 处理 PUBREL —— 完成 QoS 2 投递并回 PUBCOMP。
         *
         * @param flags  首字节低 4 位（MQTT 3.1.1 要求 PUBREL 为 0x2）
         * @param packet 报文内容
         */
        private void handlePubrel(int flags, byte[] packet) throws IOException {
            if (flags != 0x02) {
                throw new MqttProtocolViolation("PUBREL 标志位必须为 0x2，实际 0x" + Integer.toHexString(flags));
            }
            PacketReader r = new PacketReader(packet);
            int packetId = r.unsignedShort("报文标识符");
            PendingPublish pending = pendingQos2.remove(packetId);
            if (pending != null) {
                deliver(pending.topic(), pending.message(), pending.qos());
            }
            sendPubcomp(packetId);
        }

        /**
         * 处理订阅
         *
         * @param packet 报文内容
        */
        private void handleSubscribe(byte[] packet) throws IOException {
            PacketReader r = new PacketReader(packet);
            int packetId = r.unsignedShort("报文标识符");
            if (packetId == 0) {
                throw new MqttProtocolViolation("SUBSCRIBE 报文标识符不能为 0");
            }
            if (!r.hasRemaining()) {
                throw new MqttProtocolViolation("SUBSCRIBE 至少包含一个主题过滤器");
            }
            ByteArrayOutputStream returnCodes = new ByteArrayOutputStream();
            while (r.hasRemaining()) {
                String topic = r.string("主题过滤器");
                int requested = r.unsignedByte("请求 QoS") & 0x03;
                if (!MqttTopics.isValidFilter(topic)) {
                    // 0x80 = 订阅失败，仅拒绝该过滤器，保持连接
                    returnCodes.write(0x80);
                    log.warn("MQTT 客户端 {} 订阅了非法主题过滤器: {}", clientId, topic);
                    continue;
                }
                int granted = Math.min(requested, maxSupportedQos);
                subscriptions.put(topic, granted);
                returnCodes.write(granted);
                log.debug("MQTT 客户端 {} 订阅: topic={}, requested={}, granted={}",
                        clientId, topic, requested, granted);
            }
            sendSuback(packetId, returnCodes.toByteArray());
        }

        /**
         * 处理取消订阅
         *
         * @param packet 报文内容
        */
        private void handleUnsubscribe(byte[] packet) throws IOException {
            PacketReader r = new PacketReader(packet);
            int packetId = r.unsignedShort("报文标识符");
            while (r.hasRemaining()) {
                String topic = r.string("主题过滤器");
                subscriptions.remove(topic);
                log.debug("MQTT 客户端 {} 取消订阅: {}", clientId, topic);
            }
            sendUnsuback(packetId);
        }

        /**
         * 发送Suback
         *
         * @param packetId    数据包标识
         * @param returnCodes 每个订阅授予的 QoS
         */
        private void sendSuback(int packetId, byte[] returnCodes) throws IOException {
            byte[] body = new byte[returnCodes.length + 2];
            body[0] = (byte) ((packetId >> 8) & 0xFF);
            body[1] = (byte) (packetId & 0xFF);
            System.arraycopy(returnCodes, 0, body, 2, returnCodes.length);
            writePacket(0x90, body);
        }

        /**
         * 发送Unsuback
         *
         * @param packetId 数据包标识
         */
        private void sendUnsuback(int packetId) throws IOException {
            writePacket(0xB0, new byte[]{(byte) ((packetId >> 8) & 0xFF), (byte) (packetId & 0xFF)});
        }

        /**
         * 处理Pingreq
        */
        private void handlePingreq() throws IOException {
            writePacket(0xD0, new byte[0]);
        }

        /**
         * 发送Connack
         *
         * @param returnCode 返回码
        */
        private void sendConnack(int returnCode) throws IOException {
            writePacket(0x20, new byte[]{0x00, (byte) returnCode});
        }

        /**
         * 发送Puback
         *
         * @param packetId 数据包标识
         */
        private void sendPuback(int packetId) throws IOException {
            writePacket(0x40, idBytes(packetId));
        }

        /**
         * 发送Pubrec
         *
         * @param packetId 数据包标识
         */
        private void sendPubrec(int packetId) throws IOException {
            writePacket(0x50, idBytes(packetId));
        }

        /**
         * 发送Pubcomp
         *
         * @param packetId 数据包标识
         */
        private void sendPubcomp(int packetId) throws IOException {
            writePacket(0x70, idBytes(packetId));
        }

        /**
         * 报文标识符字节。
         *
         * @param packetId 报文标识符
         * @return 双字节大端数组
         */
        private static byte[] idBytes(int packetId) {
            return new byte[]{(byte) ((packetId >> 8) & 0xFF), (byte) (packetId & 0xFF)};
        }

        /**
         * 发送发布
         *
         * @param topic topic
         * @param payload payload
         * @param qos qos
         */
        private void sendPublish(String topic, String payload, int qos) throws IOException {
            byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[2 + topicBytes.length + (qos > 0 ? 2 : 0) + payloadBytes.length];
            int pos = 0;
            body[pos++] = (byte) ((topicBytes.length >> 8) & 0xFF);
            body[pos++] = (byte) (topicBytes.length & 0xFF);
            System.arraycopy(topicBytes, 0, body, pos, topicBytes.length);
            pos += topicBytes.length;
            if (qos > 0) {
                int id;
                synchronized (writeLock) {
                    nextPacketId = nextPacketId == 0xFFFF ? 1 : nextPacketId + 1;
                    id = nextPacketId;
                }
                body[pos++] = (byte) ((id >> 8) & 0xFF);
                body[pos++] = (byte) (id & 0xFF);
            }
            System.arraycopy(payloadBytes, 0, body, pos, payloadBytes.length);
            writePacket(0x30 | ((qos & 0x03) << 1), body);
        }

        /**
         * 写入一个完整控制包 —— 单锁内完成头部与载荷，保证帧不被交错。
         *
         * @param flagsByte 首字节（类型 + 标志）
         * @param body      可变头与载荷
         */
        private void writePacket(int flagsByte, byte[] body) throws IOException {
            synchronized (writeLock) {
                if (socket.isClosed()) {
                    throw new IOException("会话已关闭");
                }
                out.write(flagsByte);
                writeRemainingLength(body.length);
                out.write(body);
                out.flush();
            }
        }

        /**
         * 读取剩余长度并取出完整报文内容。
         *
         * @return 报文内容（不含固定头）
         * @throws IOException 读取异常或长度越界
         */
        private byte[] readPacket() throws IOException {
            long remainingLength = readRemainingLength();
            byte[] packet = new byte[(int) remainingLength];
            in.readFully(packet);
            return packet;
        }

        /**
         * 读取Remaining获取长度 —— 超出 {@link ServerSetting#getMaxRequestSize()} 直接判定协议违例。
         *
         * @return 剩余长度
         * @throws IOException 编码非法或超过上限
         */
        private long readRemainingLength() throws IOException {
            int multiplier = 1;
            long value = 0;
            int digit;
            int encoded = 0;
            do {
                digit = in.readUnsignedByte();
                if (++encoded > 4) {
                    throw new MqttProtocolViolation("剩余长度编码超过 4 字节");
                }
                value += (long) (digit & 0x7F) * multiplier;
                multiplier *= 128;
            } while ((digit & 0x80) != 0);
            long limit = setting.getMaxRequestSize() > 0 ? setting.getMaxRequestSize() : 268435455L;
            limit = Math.min(limit, 268435455L);
            if (value > limit) {
                throw new MqttProtocolViolation("报文长度 " + value + " 超过上限 " + limit);
            }
            return value;
        }

        /**
         * 写入Remaining获取长度
         *
         * @param length 长度
         */
        private void writeRemainingLength(int length) throws IOException {
            do {
                int digit = length % 128;
                length = length / 128;
                if (length > 0) {
                    digit |= 0x80;
                }
                out.write(digit);
            } while (length > 0);
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!disconnectRequested) {
                publishWill();
            }
            if (connected) {
                for (Consumer<String> listener : disconnectListeners) {
                    try {
                        listener.accept(clientId);
                    } catch (Exception e) {
                        log.error("MQTT 断开回调异常", e);
                        notifyError(e);
                    }
                }
                dispatchAnnotatedMethods(onCloseMethods);
            }
            connected = false;
            subscriptions.clear();
            pendingQos2.clear();
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
            // 仅当映射中仍是本会话时移除，避免误删同 clientId 的新会话
            clients.remove(sessionKey, this);
        }
    }

    /**
     * 等待 PUBREL 的 QoS 2 消息。
     *
     * @param topic   主题
     * @param message 消息
     * @param qos     发布 QoS
     */
    private record PendingPublish(String topic, String message, int qos) {
    }

    /**
     * 报文游标 —— 所有读取都带边界校验，畸形报文抛协议违例而不是数组越界。
     *
     * @author CH
     * @since 4.0.0
     */
    private static final class PacketReader {

        /**
         * 报文内容
         */
        private final byte[] buf;

        /**
         * 当前偏移
         */
        private int pos;

        PacketReader(byte[] buf) {
            this.buf = buf;
        }

        /**
         * 是否还有未读取内容。
         *
         * @return 有剩余返回 true
         */
        boolean hasRemaining() {
            return pos < buf.length;
        }

        /**
         * 读取单字节。
         *
         * @param label 字段名（异常信息用）
         * @return 无符号字节值
         */
        int unsignedByte(String label) {
            require(label, 1);
            return buf[pos++] & 0xFF;
        }

        /**
         * 读取双字节大端无符号数。
         *
         * @param label 字段名
         * @return 数值
         */
        int unsignedShort(String label) {
            require(label, 2);
            int value = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            pos += 2;
            return value;
        }

        /**
         * 读取长度前缀字符串。
         *
         * @param label 字段名
         * @return UTF-8 字符串
         */
        String string(String label) {
            int length = unsignedShort(label + "长度");
            return new String(bytesOf(label, length), StandardCharsets.UTF_8);
        }

        /**
         * 读取长度前缀字节数组。
         *
         * @param label 字段名
         * @return 字节内容
         */
        byte[] bytes(String label) {
            return bytesOf(label, unsignedShort(label + "长度"));
        }

        /**
         * 读取剩余全部字节。
         *
         * @return 剩余内容
         */
        byte[] tail() {
            return bytesOf("载荷", buf.length - pos);
        }

        /**
         * 读取固定长度字节。
         *
         * @param label  字段名
         * @param length 长度
         * @return 字节内容
         */
        private byte[] bytesOf(String label, int length) {
            require(label, length);
            byte[] out = new byte[length];
            System.arraycopy(buf, pos, out, 0, length);
            pos += length;
            return out;
        }

        /**
         * 边界校验。
         *
         * @param label  字段名
         * @param length 需要的字节数
         */
        private void require(String label, int length) {
            if (length < 0 || pos + length > buf.length) {
                throw new MqttProtocolViolation("报文残缺或字段越界: " + label
                        + "（需要 " + length + " 字节，剩余 " + (buf.length - pos) + " 字节）");
            }
        }
    }

    // ==================== 异常类 ====================
    /**
     * 协议违例 —— 报文畸形或顺序非法，仅结束当前会话。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MqttProtocolViolation extends RuntimeException {

        MqttProtocolViolation(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * mqtt服务端异常类。
     *
     * @author CH
     * @since 4.0.0
     */

    public static class MqttServerException extends RuntimeException {
        /**
         * 创建 mqtt服务端异常 实例
         * @param message 消息
         * @param cause Throwable
         * @param cause cause
         */
        public MqttServerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * MQTT 连接凭据校验器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    @FunctionalInterface
    public interface MqttAuthenticator {

        /**
         * 校验连接凭据。
         *
         * @param clientId 客户端标识
         * @param username 用户名，CONNECT 未携带时为 {@code null}
         * @param password 密码，CONNECT 未携带时为 {@code null}
         * @return 允许接入返回 true
         */
        boolean authenticate(String clientId, String username, String password);
    }
}
