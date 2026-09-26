package com.chua.mqtt.support.client;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.mqtt.support.server.MqttServer;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MQTT 链式客户端冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.mqtt.support.client.MqttClientWrapperSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>被测对象是 Paho {@code MqttClient} 之上的链式包装，因此必须接一台真 Broker 才谈得上"收到消息"：
 * 这里用项目自带的内嵌 {@link MqttServer} 承担 Broker 角色，全部流量走 127.0.0.1 回环随机端口。
 * 覆盖注解订阅下发、自动重连后的订阅恢复、未启动即使用的快速失败、非法过滤器/主题拒绝，
 * 以及异步发布不被 Broker 应答阻塞。</p>
 *
 * <p>"静默 Broker" 用例会只回 CONNACK、其余报文一律不应答，用来暴露"标称异步实则同步等待"的实现；
 * 该用例在旧实现上会阻塞，故反射调用被放进带超时的线程里。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MqttClientWrapperSmokeTest {

    /**
     * 通过计数
     */
    private static int passCount = 0;
    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 单次投递等待上限（毫秒）
     */
    private static final int WAIT_MILLIS = 3000;
    /**
     * 重连恢复等待上限（毫秒）
     */
    private static final int RECONNECT_MILLIS = 20000;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 启动失败
     */
    public static void main(String[] args) throws Exception {
        MqttServer server = startServer(pickFreePort());
        try {
            annotatedOnMessageSubscribesAtBroker(server);
            invalidFilterAndTopicRejected(server);
        } finally {
            server.stop();
        }

        subscriptionsRestoredAfterReconnect();
        useBeforeStartFailsFast();
        asyncSendReturnsBeforeBrokerAck();

        System.out.println("pass=" + passCount + ", fail=" + failureCount);
        if (failureCount > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
        System.exit(0);
    }

    // ==================== 用例 ====================

    /**
     * {@code @OnMessage} 只在本地登记回调是不够的，必须把主题过滤器下发到 Broker 才收得到消息。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void annotatedOnMessageSubscribesAtBroker(MqttServer server) throws Exception {
        AtomicInteger annotated = new AtomicInteger();
        AtomicInteger listener = new AtomicInteger();
        try (MqttClientWrapper sub = client(server, "wrap-annotated-sub");
             MqttClientWrapper pub = client(server, "wrap-annotated-pub")) {
            sub.register(new AnnotatedHandler(annotated));
            sub.onMessage("annotated/#", (topic, payload) -> listener.incrementAndGet());
            sub.start();
            pub.start();

            pub.publish().topic("annotated/created").payload("m1").qos(1).send();
            waitUntil(() -> annotated.get() + listener.get() > 0, WAIT_MILLIS);
            System.out.println("OBSERVE annotatedHits=" + annotated.get() + " listenerHits=" + listener.get());
            check("注解处理器收到 Broker 投递", annotated.get() == 1);
            check("同一过滤器只投递一次", listener.get() == 1);
        }
    }

    /**
     * 断线自动重连后，必须按登记过的过滤器重新订阅。
     *
     * <p>Broker 重启后连接侧订阅关系即失效，若包装层不重新下发，客户端会静默退化成"只发不收"，
     * 而 {@code publish} 仍然正常，故仅看客户端状态无法发现问题，必须实测投递次数。</p>
     *
     * @throws Exception 连接失败
     */
    private static void subscriptionsRestoredAfterReconnect() throws Exception {
        int port = pickFreePort();
        MqttServer server = startServer(port);
        AtomicInteger hits = new AtomicInteger();
        try (MqttClientWrapper sub = client(port, "wrap-recon-sub");
             MqttClientWrapper pub = client(port, "wrap-recon-pub")) {
            sub.start();
            pub.start();
            sub.subscribe().topic("recon/device").qos(1).handler((topic, payload) -> hits.incrementAndGet()).start();

            pub.publish().topic("recon/device").payload("before").qos(1).send();
            waitUntil(() -> hits.get() > 0, WAIT_MILLIS);
            int before = hits.get();
            System.out.println("OBSERVE beforeRestartHits=" + before);
            check("重启前订阅可投递", before == 1);

            server.stop();
            server = null;
            MqttServer revived = restartServer(port);
            try {
                int beforeReconnect = hits.get();
                long deadline = System.currentTimeMillis() + RECONNECT_MILLIS;
                while (hits.get() == beforeReconnect && System.currentTimeMillis() < deadline) {
                    quietlyPublish(pub);
                    Thread.sleep(200);
                }
                System.out.println("OBSERVE afterReconnectHits=" + (hits.get() - beforeReconnect)
                        + " reconnecting=" + sub.isConnected());
                check("重连后连接状态恢复", sub.isConnected());
                check("重连后原订阅继续投递", hits.get() > beforeReconnect);
            } finally {
                revived.stop();
            }
        }
    }

    /**
     * 未启动就使用订阅/发布，应给出可定位的状态异常而不是空指针。
     *
     * @throws Exception 反射失败
     */
    private static void useBeforeStartFailsFast() throws Exception {
        MqttClientWrapper idle = MqttClientWrapper.builder()
                .broker("tcp://127.0.0.1:1")
                .clientId("wrap-idle")
                .build();
        Throwable onSubscribe = catchThrowable(() ->
                idle.subscribe().topic("idle/x").qos(1).handler((topic, payload) -> { }).start());
        Throwable onPublish = catchThrowable(() ->
                idle.publish().topic("idle/x").payload("p").qos(1).send());
        System.out.println("OBSERVE subscribeBeforeStart=" + describe(onSubscribe));
        System.out.println("OBSERVE publishBeforeStart=" + describe(onPublish));
        check("未启动时订阅快速失败", onSubscribe instanceof IllegalStateException);
        check("未启动时发布快速失败", onPublish instanceof IllegalStateException);
    }

    /**
     * 非法过滤器与含通配符的发布主题必须在本地被拒绝。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void invalidFilterAndTopicRejected(MqttServer server) throws Exception {
        try (MqttClientWrapper client = client(server, "wrap-invalid")) {
            client.start();
            Throwable badFilter = catchThrowable(() ->
                    client.subscribe().topic("bad/#/filter").qos(1).handler((topic, payload) -> { }).start());
            Throwable badTopic = catchThrowable(() ->
                    client.publish().topic("bad/+").payload("x").qos(0).send());
            System.out.println("OBSERVE invalidFilter=" + describe(badFilter));
            System.out.println("OBSERVE invalidTopic=" + describe(badTopic));
            check("非法过滤器被拒绝", badFilter instanceof IllegalArgumentException);
            check("发布主题含通配符被拒绝", badTopic instanceof IllegalArgumentException);
        }
    }

    /**
     * 异步发布不得等 Broker 应答才返回。
     *
     * <p>对端只回 CONNACK、永不回 PUBACK，因此同步实现会一直阻塞到超时；
     * 用反射取返回值，兼容旧实现里 {@code sendAsync} 返回 {@code void} 的形态。</p>
     *
     * @throws Exception 反射失败
     */
    private static void asyncSendReturnsBeforeBrokerAck() throws Exception {
        try (SilentBroker broker = new SilentBroker();
             MqttClientWrapper client = client(broker.port(), "wrap-async")) {
            client.start();
            MqttClientWrapper.PublishOperation operation = client.publish()
                    .topic("async/pending")
                    .payload("body")
                    .qos(1);
            Method sendAsync = MqttClientWrapper.PublishOperation.class.getMethod("sendAsync");
            AtomicReference<Object> returned = new AtomicReference<>();
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    returned.set(sendAsync.invoke(operation));
                } catch (Throwable e) {
                    failed.set(e);
                }
            }, "async-sender");
            worker.setDaemon(true);
            long begin = System.currentTimeMillis();
            worker.start();
            worker.join(WAIT_MILLIS);
            long elapsed = System.currentTimeMillis() - begin;
            boolean returnedImmediately = !worker.isAlive();
            boolean pending = returned.get() instanceof IMqttDeliveryToken token && !token.isComplete();
            System.out.println("OBSERVE asyncReturned=" + returnedImmediately + " elapsed=" + elapsed
                    + "ms returnType=" + (returned.get() == null ? "void" : returned.get().getClass().getSimpleName())
                    + " tokenPending=" + pending
                    + " error=" + describe(failed.get()));
            check("异步发布不被 Broker 应答阻塞", returnedImmediately && pending);
        }
    }

    // ==================== 工具 ====================

    /**
     * 取一个空闲端口，供"停机后原端口重启"使用。
     *
     * @return 空闲端口
     * @throws IOException 端口探测失败
     */
    private static int pickFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 启动一台仅监听回环地址的服务端。
     *
     * @param port 监听端口
     * @return 已启动的服务端
     */
    private static MqttServer startServer(int port) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setShutdownQuietPeriod(0);
        MqttServer server = new MqttServer(setting);
        server.start();
        return server;
    }

    /**
     * 在同一个端口上重新拉起服务端，模拟 Broker 重启。
     *
     * @param port 端口
     * @return 已启动的服务端
     */
    private static MqttServer restartServer(int port) {
        Exception last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                return startServer(port);
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new IllegalStateException("端口未能复用: " + port, last);
    }

    /**
     * 构造指向服务端的客户端。
     *
     * @param server   服务端
     * @param clientId 客户端标识
     * @return 未启动的客户端
     */
    private static MqttClientWrapper client(MqttServer server, String clientId) {
        return client(server.getPort(), clientId);
    }

    /**
     * 构造指向指定端口的客户端。
     *
     * @param port     Broker 端口
     * @param clientId 客户端标识
     * @return 未启动的客户端
     */
    private static MqttClientWrapper client(int port, String clientId) {
        return MqttClientWrapper.builder()
                .broker("tcp://127.0.0.1:" + port)
                .clientId(clientId)
                .keepAlive(5)
                .connectionTimeout(3)
                .automaticReconnect(true)
                .build();
    }

    /**
     * 重连窗口内发布失败是预期现象，吞掉异常继续重试。
     *
     * @param publisher 发布端
     */
    private static void quietlyPublish(MqttClientWrapper publisher) {
        try {
            publisher.publish().topic("recon/device").payload("after").qos(1).send();
        } catch (Exception ignored) {
            // 连接尚未恢复，下一轮再试
        }
    }

    /**
     * 轮询等待条件成立。
     *
     * @param condition 条件
     * @param millis    最长等待
     * @throws InterruptedException 中断
     */
    private static void waitUntil(BooleanCondition condition, int millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /**
     * 执行动作并捕获抛出的一切异常。
     *
     * @param action 动作
     * @return 异常，未抛出时为空
     */
    private static Throwable catchThrowable(Action action) {
        try {
            action.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    /**
     * 异常摘要：类型 + 消息。
     *
     * @param throwable 异常
     * @return 摘要文本
     */
    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "none";
        }
        Throwable cause = throwable.getCause() != null && throwable instanceof java.lang.reflect.InvocationTargetException
                ? throwable.getCause() : throwable;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /**
     * 校验项。
     *
     * @param name   名称
     * @param passed 是否通过
     */
    private static void check(String name, boolean passed) {
        if (passed) {
            passCount++;
            System.out.println("  ok   " + name);
        } else {
            failureCount++;
            System.out.println("  FAIL " + name);
        }
    }

    /**
     * 布尔条件。
     */
    private interface BooleanCondition {
        /**
         * 判定。
         *
         * @return 是否成立
         */
        boolean test();
    }

    /**
     * 可抛异常的动作。
     */
    private interface Action {
        /**
         * 执行。
         *
         * @throws Exception 执行失败
         */
        void run() throws Exception;
    }

    /**
     * 带 {@code @OnMessage} 注解的处理器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static class AnnotatedHandler {

        /**
         * 命中计数
         */
        private final AtomicInteger hits;

        /**
         * 构造处理器。
         *
         * @param hits 命中计数
         */
        public AnnotatedHandler(AtomicInteger hits) {
            this.hits = hits;
        }

        /**
         * 接收注解主题的消息。
         *
         * @param payload 消息体
         */
        @OnMessage("annotated/#")
        public void onEvent(String payload) {
            hits.incrementAndGet();
        }
    }

    /**
     * 只回 CONNACK 的静默 Broker，用于制造"永远等不到应答"的发布。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static final class SilentBroker implements AutoCloseable {

        /**
         * 监听套接字
         */
        private final ServerSocket serverSocket;
        /**
         * 受理线程
         */
        private final Thread worker;

        /**
         * 启动静默 Broker。
         *
         * @throws IOException 端口绑定失败
         */
        private SilentBroker() throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            worker = new Thread(this::serve, "silent-broker");
            worker.setDaemon(true);
            worker.start();
        }

        /**
         * 监听端口。
         *
         * @return 端口
         */
        private int port() {
            return serverSocket.getLocalPort();
        }

        /**
         * 受理一条连接：CONNECT 回 CONNACK，其余报文只读不应答。
         */
        private void serve() {
            try (Socket socket = serverSocket.accept();
                 DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                while (true) {
                    int header = in.read();
                    if (header < 0) {
                        return;
                    }
                    in.readNBytes(readRemainingLength(in));
                    if ((header >> 4) == 1) {
                        out.write(new byte[]{0x20, 0x02, 0x00, 0x00});
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // 对端关闭即结束
            }
        }

        /**
         * 读取剩余长度字段（MQTT 变长编码）。
         *
         * @param in 输入流
         * @return 剩余长度
         * @throws IOException 读失败
         */
        private static int readRemainingLength(DataInputStream in) throws IOException {
            int multiplier = 1;
            int value = 0;
            int digit;
            do {
                digit = in.read();
                if (digit < 0) {
                    throw new IOException("流已结束");
                }
                value += (digit & 127) * multiplier;
                multiplier *= 128;
            } while ((digit & 128) != 0);
            return value;
        }

        @Override
        /**
         * 关闭
        */
        public void close() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
        }
    }
}
