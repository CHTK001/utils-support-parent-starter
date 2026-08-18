package com.chua.mqtt.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * MQTT 嵌入式服务器，轻量级实现。
 * <p>
 * 继承 {@link AbstractServer}，支持 {@link ServerFilter} 过滤器链、
 * {@link OnOpen}/{@link OnClose}/{@link OnMessage} 注解处理。
 * 基于原生 ServerSocket 实现 MQTT 3.1.1 协议，支持 CONNECT/PUBLISH/SUBSCRIBE/UNSUBSCRIBE/PINGREQ/DISCONNECT。
 * </p>
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
     * 原生 MQTT 主题订阅处理器（保留原有 topicSubscribers 机制）
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
     * 接收连接线程池
     */
    private ExecutorService bossPool;

    /**
     * 处理消息线程池
     */
    private ExecutorService workerPool;

    /**
     * ServerSocket 实例
     */
    private ServerSocket serverSocket;

    public MqttServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(setting.isSoReuseAddr());
            serverSocket.setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384));
            serverSocket.bind(addr, Math.max(setting.getBacklog(), 2048));
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(serverSocket.getLocalPort());
            bossPool = Executors.newVirtualThreadPerTaskExecutor();
            workerPool = Executors.newVirtualThreadPerTaskExecutor();
            running = true;

            bossPool.submit(this::acceptLoop);
            log.info("MQTT 服务器启动: {}:{} (backlog={}, virtualThreads=true)",
                    setting.getHost(), setting.getPort(), Math.max(setting.getBacklog(), 2048));
        } catch (IOException e) {
            throw new RuntimeException("MQTT 服务器启动失败", e);
        }
    }

    @Override
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
    public ProtocolType getProtocolType() {
        return ProtocolType.MQTT;
    }

    /**
     * 注册主题订阅处理器。
     *
     * @param topic   主题名称，支持通配符 # 和 +
     * @param handler 消息处理器
     * @return 当前服务器实例，支持链式调用
     */
    public MqttServer onSubscribe(String topic, BiConsumer<String, String> handler) {
        topicSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 向所有订阅了指定 topic 的客户端推送消息。
     *
     * @param topic   主题名称
     * @param payload 消息内容
     */
    public void publish(String topic, String payload) {
        for (Map.Entry<String, List<BiConsumer<String, String>>> entry : topicSubscribers.entrySet()) {
            if (matchTopic(entry.getKey(), topic)) {
                for (BiConsumer<String, String> handler : entry.getValue()) {
                    try {
                        handler.accept(topic, payload);
                    } catch (Exception e) {
                        log.error("MQTT 推送异常", e);
                        for (Consumer<Throwable> listener : errorListeners) {
                            try {
                                listener.accept(e);
                            } catch (Exception ex) {
                                log.error("MQTT 错误监听器执行异常", ex);
                            }
                        }
                        dispatchAnnotatedMethods(onErrorMethods, e);
                    }
                }
            }
        }
    }

    // ==================== 注解方法缓存 ====================

    /** ONopenmethods */
    private final List<AnnotatedMethod> onOpenMethods = new CopyOnWriteArrayList<>();
    /** ONclosemethods */
    private final List<AnnotatedMethod> onCloseMethods = new CopyOnWriteArrayList<>();
    /** ON消息methods */
    private final List<AnnotatedMessage> onMessageMethods = new CopyOnWriteArrayList<>();
    /** ON错误methods */
    private final List<AnnotatedMethod> onErrorMethods = new CopyOnWriteArrayList<>();

    @Override
    public MqttServer registerBean(Object handler) {
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

    private record AnnotatedMethod(Class<?> beanClass, Method method) {}
    private record AnnotatedMessage(Class<?> beanClass, Method method, String topic) {}

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

    private void dispatchAnnotatedPublish(String topic, String payload) {
        ObjectContext ctx = getObjectContext();
        if (ctx == null) {
            return;
        }
        for (AnnotatedMessage entry : onMessageMethods) {
            if (matchTopic(entry.topic(), topic)) {
                Object bean = ctx.getBeanOfType(entry.beanClass());
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
            log.error("MQTT 注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), cause);
            for (Consumer<Throwable> listener : errorListeners) {
                try {
                    listener.accept(cause);
                } catch (Exception ex) {
                    log.error("MQTT 错误监听器执行异常", ex);
                }
            }
            dispatchAnnotatedMethods(onErrorMethods, cause);
        } catch (Exception e) {
            log.error("MQTT 注解方法调用异常: {}.{}", bean.getClass().getSimpleName(), method.getName(), e);
            dispatchAnnotatedMethods(onErrorMethods, e);
        }
    }

    private void invokeMethod(Object handler, Method method, Object... args) {
        try {
            method.invoke(handler, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("MQTT 注解方法调用异常: {}.{}", handler.getClass().getSimpleName(), method.getName(), cause);
            for (Consumer<Throwable> listener : errorListeners) {
                try {
                    listener.accept(cause);
                } catch (Exception ex) {
                    log.error("MQTT 错误监听器执行异常", ex);
                }
            }
        } catch (Exception e) {
            log.error("MQTT 注解方法调用异常: {}.{}", handler.getClass().getSimpleName(), method.getName(), e);
            for (Consumer<Throwable> listener : errorListeners) {
                try {
                    listener.accept(e);
                } catch (Exception ex) {
                    log.error("MQTT 错误监听器执行异常", ex);
                }
            }
        }
    }

    private static boolean matchTopic(String pattern, String topic) {
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
            if ("+".equals(pp[p])) {
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

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(setting.isTcpNoDelay());
                String clientId = "client-" + socket.hashCode();
                ClientSession session = new ClientSession(clientId, socket);
                clients.put(clientId, session);
                workerPool.submit(session::handleLoop);
            } catch (IOException e) {
                if (running) {
                    log.error("MQTT 接受连接异常", e);
                }
                dispatchAnnotatedMethods(onErrorMethods, e);
            }
        }
    }

    // ==================== 客户端会话 ====================

    private class ClientSession {
        /** 客户端ID */
        private String clientId;
        /** Socket */
        private final Socket socket;
        /** IN */
        private final DataInputStream in;
        /** OUT */
        private final DataOutputStream out;
        /** Subscriptions */
        private final Set<String> subscriptions = new CopyOnWriteArraySet<>();

        ClientSession(String clientId, Socket socket) throws IOException {
            this.clientId = clientId;
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        void handleLoop() {
            try {
                while (running && !socket.isClosed()) {
                    int firstByte = in.readUnsignedByte();
                    int type = (firstByte >> 4) & 0x0F;

                    switch (type) {
                        case 1 -> handleConnect();
                        case 3 -> handlePublish(firstByte);
                        case 8 -> handleSubscribe();
                        case 10 -> handleUnsubscribe();
                        case 12 -> handlePingreq();
                        case 14 -> handleDisconnect();
                        default -> skipPacket();
                    }
                }
            } catch (EOFException e) {
                // 客户端正常断开
            } catch (IOException e) {
                if (running) {
                    log.debug("MQTT 客户端 {} 读取异常: {}", clientId, e.getMessage());
                }
                for (Consumer<Throwable> el : errorListeners) {
                    try {
                        el.accept(e);
                    } catch (Exception ex) {
                        log.error("MQTT 错误监听器执行异常", ex);
                    }
                }
                        dispatchAnnotatedMethods(onErrorMethods, e);
                    } finally {
                        handleDisconnect();
                close();
            }
        }

        private void handlePublish(int firstByte) throws IOException {
            int remainingLength = readRemainingLength();
            byte[] packet = new byte[remainingLength];
            in.readFully(packet);

            int pos = 0;
            int topicLength = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
            pos += 2;
            String topic = new String(packet, pos, topicLength, StandardCharsets.UTF_8);
            pos += topicLength;

            int qos = (firstByte >> 1) & 0x03;
            int packetId = 0;
            if (qos > 0 && pos + 1 < remainingLength) {
                packetId = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
                pos += 2;
            }

            byte[] payload = new byte[remainingLength - pos];
            System.arraycopy(packet, pos, payload, 0, payload.length);
            String message = new String(payload, StandardCharsets.UTF_8);

            log.debug("MQTT 收到: topic={}, qos={}, payload={}", topic, qos, message);

            for (Map.Entry<String, List<BiConsumer<String, String>>> entry : topicSubscribers.entrySet()) {
                if (matchTopic(entry.getKey(), topic)) {
                    for (BiConsumer<String, String> handler : entry.getValue()) {
                        try {
                            handler.accept(topic, message);
                        } catch (Exception e) {
                            log.error("MQTT 消息处理异常", e);
                            for (Consumer<Throwable> el : errorListeners) {
                                try {
                                    el.accept(e);
                                } catch (Exception ex) {
                                    log.error("MQTT 错误监听器执行异常", ex);
                                }
                            }
                            dispatchAnnotatedMethods(onErrorMethods, e);
                        }
                    }
                }
            }

            broadcastToSubscribers(topic, message, qos);

            dispatchAnnotatedPublish(topic, message);

            if (qos == 1) {
                sendPuback(packetId);
            }
        }

        private void handleConnect() throws IOException {
            int remainingLength = readRemainingLength();
            byte[] packet = new byte[remainingLength];
            in.readFully(packet);

            int pos = 0;
            int protocolNameLength = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
            pos += 2 + protocolNameLength;
            pos++;
            pos++;
            pos += 2;

            int clientIdLength = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
            pos += 2;
            if (clientIdLength > 0) {
                this.clientId = new String(packet, pos, clientIdLength, StandardCharsets.UTF_8);
            }

            log.debug("MQTT CONNECT: clientId={}", clientId);
            sendConnack();

            for (Consumer<String> listener : connectListeners) {
                try {
                    listener.accept(clientId);
                } catch (Exception e) {
                    log.error("MQTT 连接回调异常", e);
                    for (Consumer<Throwable> el : errorListeners) {
                        try {
                            el.accept(e);
                        } catch (Exception ex) {
                            log.error("MQTT 错误监听器执行异常", ex);
                        }
                    }
                }
            }
            dispatchAnnotatedMethods(onOpenMethods);
        }

        private void handleDisconnect() {
            for (Consumer<String> listener : disconnectListeners) {
                try {
                    listener.accept(clientId);
                } catch (Exception e) {
                    log.error("MQTT 断开回调异常", e);
                    for (Consumer<Throwable> el : errorListeners) {
                        try {
                            el.accept(e);
                        } catch (Exception ex) {
                            log.error("MQTT 错误监听器执行异常", ex);
                        }
                    }
                }
            }
            dispatchAnnotatedMethods(onCloseMethods);
        }

        private void handleSubscribe() throws IOException {
            int remainingLength = readRemainingLength();
            byte[] packet = new byte[remainingLength];
            in.readFully(packet);

            int pos = 0;
            int packetId = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
            pos += 2;

            while (pos < remainingLength) {
                int topicLength = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
                pos += 2;
                String topic = new String(packet, pos, topicLength, StandardCharsets.UTF_8);
                pos += topicLength;
                int qos = packet[pos] & 0x03;
                pos++;
                subscriptions.add(topic);
                log.debug("MQTT 客户端 {} 订阅: topic={}, qos={}", clientId, topic, qos);
            }

            sendSuback(packetId);
        }

        private void handleUnsubscribe() throws IOException {
            int remainingLength = readRemainingLength();
            byte[] packet = new byte[remainingLength];
            in.readFully(packet);

            int pos = 0;
            int packetId = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
            pos += 2;

            while (pos < remainingLength) {
                int topicLength = ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
                pos += 2;
                String topic = new String(packet, pos, topicLength, StandardCharsets.UTF_8);
                pos += topicLength;
                subscriptions.remove(topic);
                log.debug("MQTT 客户端 {} 取消订阅: {}", clientId, topic);
            }

            sendUnsuback(packetId);
        }

        private void sendSuback(int packetId) throws IOException {
            out.write(0x70);
            out.write(0x03);
            out.write((packetId >> 8) & 0xFF);
            out.write(packetId & 0xFF);
            out.write(0x00);
            out.flush();
        }

        private void sendUnsuback(int packetId) throws IOException {
            out.write(0xB0);
            out.write(0x02);
            out.write((packetId >> 8) & 0xFF);
            out.write(packetId & 0xFF);
            out.flush();
        }

        private void handlePingreq() throws IOException {
            out.write(0xD0);
            out.write(0x00);
            out.flush();
        }

        private void sendConnack() throws IOException {
            out.write(0x20);
            out.write(0x02);
            out.write(0x00);
            out.write(0x00);
            out.flush();
        }

        private void sendPuback(int packetId) throws IOException {
            out.write(0x40);
            out.write(0x02);
            out.write((packetId >> 8) & 0xFF);
            out.write(packetId & 0xFF);
            out.flush();
        }

        private void broadcastToSubscribers(String topic, String message, int qos) {
            for (ClientSession other : clients.values()) {
                for (String sub : other.subscriptions) {
                    if (matchTopic(sub, topic)) {
                        try {
                            other.sendPublish(topic, message, qos);
                        } catch (IOException e) {
                            log.debug("MQTT 转发消息失败: {}", e.getMessage());
                        }
                        break;
                    }
                }
            }
        }

        private void sendPublish(String topic, String payload, int qos) throws IOException {
            byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            int remainingLength = 2 + topicBytes.length + payloadBytes.length;

            int flags = 0x30 | ((qos & 0x03) << 1);
            out.write(flags);
            writeRemainingLength(remainingLength);
            out.writeShort(topicBytes.length);
            out.write(topicBytes);
            out.write(payloadBytes);
            out.flush();
        }

        private int readRemainingLength() throws IOException {
            int multiplier = 1;
            int value = 0;
            int digit;
            do {
                digit = in.readUnsignedByte();
                value += (digit & 0x7F) * multiplier;
                multiplier *= 128;
            } while ((digit & 0x80) != 0);
            return value;
        }

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

        private void skipPacket() throws IOException {
            int remainingLength = readRemainingLength();
            byte[] skip = new byte[remainingLength];
            in.readFully(skip);
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            clients.remove(clientId);
        }
    }

    // ==================== 异常类 ====================

    public static class MqttServerException extends RuntimeException {
        public MqttServerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
