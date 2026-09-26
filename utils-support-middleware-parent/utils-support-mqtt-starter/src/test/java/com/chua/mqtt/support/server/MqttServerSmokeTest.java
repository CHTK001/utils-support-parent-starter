package com.chua.mqtt.support.server;

import com.chua.common.support.network.server.ServerSetting;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MQTT 内嵌服务端冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.mqtt.support.server.MqttServerSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>全部流量走 127.0.0.1 随机端口的本机 socket，报文由测试内地手写 MQTT 3.1.1 编解码，
 * 不依赖 Paho 等第三方客户端。覆盖服务端发布、通配符语义、QoS 1/2 握手、
 * 会话回收、遗嘱投递、凭据校验、协议违例收口与并发写出帧完整性。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MqttServerSmokeTest {

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 通过计数
     */
    private static int passCount = 0;
    /**
     * 默认读超时（毫秒）
     */
    private static final int READ_TIMEOUT = 2000;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 启动失败
     */
    public static void main(String[] args) throws Exception {
        MqttServer server = startServer(null, 1);
        MqttServer qos0Server = startServer(null, 0);
        MqttServer authServer = startServer((clientId, username, password) ->
                "u".equals(username) && "p".equals(password), 1);
        try {
            serverPublishReachesWireSubscriber(server);
            wildcardMatchesParentTopic(server);
            qos1OutboundCarriesPacketIdentifier(server);
            qos2HandshakeDeliversOnce(server);
            sessionRemovedAfterAbruptClose(server);
            willPublishedOnAbruptClose(server);
            grantedQosHonoursServerMax(server, qos0Server);
            authenticatorRejectsBadCredentials(authServer);
            firstPacketMustBeConnect(server);
            oversizedDeclaredLengthRejected(server);
            invalidTopicAndFilterRejected(server);
            concurrentPublishKeepsFramesIntact(server);
        } finally {
            server.stop();
            qos0Server.stop();
            authServer.stop();
        }

        System.out.println("pass=" + passCount + ", fail=" + failureCount);
        if (failureCount > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    // ==================== 用例 ====================

    /**
     * 服务端主动发布必须到达在线的协议订阅者。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void serverPublishReachesWireSubscriber(MqttServer server) throws Exception {
        try (Client c = new Client(server.getPort(), "smoke-sub1")) {
            c.expectConnected();
            c.subscribe(1, "srv/time", 0);
            check("SUBACK 类型为 9", c.subscribeAckType() == 9);
            server.publish("srv/time", "hello-wire");
            Packet p = c.readPublish(READ_TIMEOUT);
            check("服务端发布到达在线订阅者", p != null);
            if (p != null) {
                check("投递主题正确", "srv/time".equals(p.topic));
                check("投递载荷正确", "hello-wire".equals(p.payload));
            }
        }
    }

    /**
     * {@code home/#} 必须匹配父级主题本身，同时保留子级匹配。
     *
     * @param server 服务端
     */
    private static void wildcardMatchesParentTopic(MqttServer server) {
        AtomicInteger hits = new AtomicInteger();
        server.onSubscribe("home/#", (topic, payload) -> hits.incrementAndGet());
        server.publish("home", "parent");
        check("# 匹配父级主题", hits.get() == 1);
        server.publish("home/room", "child");
        check("# 匹配子级主题", hits.get() == 2);
    }

    /**
     * 出站 QoS 1 必须携带报文标识符，且不回投给发起方。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void qos1OutboundCarriesPacketIdentifier(MqttServer server) throws Exception {
        try (Client a = new Client(server.getPort(), "smoke-q1-a");
             Client b = new Client(server.getPort(), "smoke-q1-b")) {
            a.expectConnected();
            b.expectConnected();
            a.subscribe(1, "q1", 1);
            check("授予 QoS 为 1", a.lastGranted == 1);
            b.subscribe(1, "q1", 1);
            b.publish("q1", "ABCDEFGH", 1);
            Packet toA = a.readPublish(READ_TIMEOUT);
            check("订阅者收到转发", toA != null);
            if (toA != null) {
                check("出站 QoS 1 载荷完整", "ABCDEFGH".equals(toA.payload));
                check("报文标识符非 0", toA.packetId != 0);
            }
            check("不回投给发起方", b.readPublish(1000) == null);
        }
    }

    /**
     * QoS 2 必须完成 PUBREC/PUBREL/PUBCOMP 四步握手后再投递。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void qos2HandshakeDeliversOnce(MqttServer server) throws Exception {
        try (Client sub = new Client(server.getPort(), "smoke-q2-sub");
             Client pub = new Client(server.getPort(), "smoke-q2-pub")) {
            sub.expectConnected();
            pub.expectConnected();
            sub.subscribe(1, "q2", 1);
            pub.publish("q2", "exactly-once", 2);
            Packet pubrec = pub.read(READ_TIMEOUT);
            check("QoS2 返回 PUBREC", pubrec != null && pubrec.type == 5);
            if (pubrec == null) {
                return;
            }
            check("QoS2 投递前订阅者无消息", sub.read(500) == null);
            ByteArrayOutputStream rel = new ByteArrayOutputStream();
            writeShort(rel, pubrec.bodyInt(0));
            pub.frame(0x62, rel);
            Packet pubcomp = pub.read(READ_TIMEOUT);
            check("QoS2 返回 PUBCOMP", pubcomp != null && pubcomp.type == 7);
            Packet delivered = sub.readPublish(READ_TIMEOUT);
            check("PUBREL 后按 QoS1 投递", delivered != null && "exactly-once".equals(delivered.payload));
        }
    }

    /**
     * 客户端异常断开后会话必须回收。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void sessionRemovedAfterAbruptClose(MqttServer server) throws Exception {
        for (int i = 0; i < 3; i++) {
            Client c = new Client(server.getPort(), "smoke-leak-" + i);
            c.expectConnected();
            c.close();
        }
        waitUntil(() -> server.getSessionCount() == 0, 3000);
        check("断开后会话表回收 (残留 " + server.getSessionCount() + ")", server.getSessionCount() == 0);
    }

    /**
     * 异常断开必须发布遗嘱，正常 DISCONNECT 不得发布。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void willPublishedOnAbruptClose(MqttServer server) throws Exception {
        try (Client watcher = new Client(server.getPort(), "smoke-will-watch")) {
            watcher.expectConnected();
            watcher.subscribe(1, "smoke/will", 0);

            Client abrupt = new Client(server.getPort(), "smoke-will-abrupt", 0x02 | 0x04, "smoke/will", "bye");
            abrupt.expectConnected();
            abrupt.subscribe(2, "smoke/own", 0);
            check("带遗嘱的 CONNECT 不破坏后续解析", abrupt.subscribeAckType() == 9);
            abrupt.close();
            Packet will = watcher.readPublish(READ_TIMEOUT);
            check("异常断开发布遗嘱", will != null && "bye".equals(will.payload));

            Client orderly = new Client(server.getPort(), "smoke-will-orderly", 0x02 | 0x04, "smoke/will", "bye2");
            orderly.expectConnected();
            orderly.disconnect();
            check("正常 DISCONNECT 不发布遗嘱", watcher.readPublish(800) == null);
        }
    }

    /**
     * 订阅授予 QoS 不得超过服务端上限。
     *
     * @param qos1   上限 1 的服务端
     * @param qos0   上限 0 的服务端
     * @throws Exception 连接失败
     */
    private static void grantedQosHonoursServerMax(MqttServer qos1, MqttServer qos0) throws Exception {
        try (Client a = new Client(qos1.getPort(), "smoke-maxqos-1");
             Client b = new Client(qos0.getPort(), "smoke-maxqos-0")) {
            a.expectConnected();
            b.expectConnected();
            a.subscribe(1, "mx", 2);
            b.subscribe(1, "mx", 2);
            check("默认上限下请求 QoS2 授予 QoS1", a.lastGranted == 1);
            check("上限 0 时降级为 QoS0", b.lastGranted == 0);
        }
    }

    /**
     * 凭据校验失败必须返回 CONNACK 0x04 并断开。
     *
     * @param authServer 带校验器的服务端
     * @throws Exception 连接失败
     */
    private static void authenticatorRejectsBadCredentials(MqttServer authServer) throws Exception {
        try (Client bad = new Client(authServer.getPort(), "smoke-bad", false)) {
            check("拒绝连接返回码 4", bad.connackCode == 4);
        }
        try (Client good = new Client(authServer.getPort(), "smoke-good", true)) {
            good.expectConnected();
            check("凭据正确可正常接入", good.connackCode == 0);
        }
    }

    /**
     * 未完成 CONNECT 就发送其他报文必须被断开。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void firstPacketMustBeConnect(MqttServer server) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 3000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeUtf(body, "early");
            body.write("x".getBytes(StandardCharsets.UTF_8));
            writeFrame(out, 0x30, body);
            check("首包非 CONNECT 被断开", closedWithin(socket));
        }
    }

    /**
     * 声明超过上限的剩余长度必须被拒绝而不是长期挂住连接。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void oversizedDeclaredLengthRejected(MqttServer server) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 3000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write(0x10);
            int length = 12 * 1024 * 1024;
            do {
                int digit = length % 128;
                length /= 128;
                out.write(length > 0 ? digit | 0x80 : digit);
            } while (length > 0);
            out.write('M');
            out.flush();
            check("超长报文声明被断开", closedWithin(socket));
        }
    }

    /**
     * 非法过滤器与含通配符的发布主题必须显式拒绝。
     *
     * @param server 服务端
     */
    private static void invalidTopicAndFilterRejected(MqttServer server) {
        boolean filterRejected = false;
        try {
            server.onSubscribe("a/#/b", (topic, payload) -> {
            });
        } catch (IllegalArgumentException e) {
            filterRejected = true;
        }
        check("非法过滤器被拒绝", filterRejected);

        boolean publishRejected = false;
        try {
            server.publish("bad/+", "x");
        } catch (IllegalArgumentException e) {
            publishRejected = true;
        }
        check("发布主题含通配符被拒绝", publishRejected);
    }

    /**
     * 多个发布线程并发写同一订阅者时帧必须保持完整。
     *
     * @param server 服务端
     * @throws Exception 连接失败
     */
    private static void concurrentPublishKeepsFramesIntact(MqttServer server) throws Exception {
        int publishers = 3;
        int each = 60;
        int expected = publishers * each;
        try (Client sub = new Client(server.getPort(), "smoke-bulk-sub")) {
            sub.expectConnected();
            sub.subscribe(1, "bulk", 0);
            final Client[] pubs = new Client[publishers];
            for (int i = 0; i < pubs.length; i++) {
                pubs[i] = new Client(server.getPort(), "smoke-bulk-pub-" + i);
                pubs[i].expectConnected();
            }
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(pubs.length);
            AtomicInteger sendFail = new AtomicInteger();
            for (int i = 0; i < pubs.length; i++) {
                final int id = i;
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        for (int k = 0; k < each; k++) {
                            pubs[id].publish("bulk", payloadOf(id * each + k), 0);
                        }
                    } catch (Exception e) {
                        sendFail.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
                thread.setDaemon(true);
                thread.start();
            }
            start.countDown();
            check("并发发布全部写出", done.await(20, TimeUnit.SECONDS) && sendFail.get() == 0);

            int frames = 0;
            int bad = 0;
            sub.socket.setSoTimeout(3000);
            try {
                while (true) {
                    Packet p = sub.readPacket();
                    if (p == null) {
                        break;
                    }
                    frames++;
                    if (p.type != 3 || !looksLikePayload(p.payload)) {
                        bad++;
                    }
                }
            } catch (IOException ignored) {
                // 读超时说明流中已无完整报文
            }
            check("帧数与发布数一致", frames == expected);
            check("并发写出无错帧", bad == 0);
        }
    }

    /**
     * 判断载荷是否保持 {@code #序号@} + 点号补齐的形态。
     *
     * @param payload 载荷
     * @return 形态完整返回 true
     */
    private static boolean looksLikePayload(String payload) {
        if (payload.length() != 32 || payload.charAt(0) != '#' || payload.charAt(payload.length() - 1) != '.') {
            return false;
        }
        int at = payload.indexOf('@');
        if (at < 2) {
            return false;
        }
        for (int i = 0; i < at - 1; i++) {
            if (!Character.isDigit(payload.charAt(i + 1))) {
                return false;
            }
        }
        return payload.chars().allMatch(c -> c == '.' || c == '#' || c == '@' || Character.isDigit(c));
    }

    // ==================== 服务端与工具 ====================

    /**
     * 启动一台仅监听回环地址的服务端。
     *
     * @param authenticator 凭据校验器，可为空
     * @param maxQos        服务端最大出站 QoS
     * @return 已启动的服务端
     */
    private static MqttServer startServer(MqttServer.MqttAuthenticator authenticator, int maxQos) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(0);
        setting.setShutdownQuietPeriod(0);
        MqttServer server = new MqttServer(setting).setMaxSupportedQos(maxQos);
        if (authenticator != null) {
            server.withAuthenticator(authenticator);
        }
        server.start();
        return server;
    }

    /**
     * 构造定长可校验载荷：{@code #序号@} + 点号补齐到 32 字节。
     *
     * @param seq 序号
     * @return 载荷
     */
    private static String payloadOf(int seq) {
        StringBuilder sb = new StringBuilder("#").append(seq).append('@');
        while (sb.length() < 32) {
            sb.append('.');
        }
        return sb.substring(0, 32);
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
     * 判断连接是否在超时前被服务端关闭。
     *
     * @param socket 套接字
     * @return 被关闭返回 true
     */
    private static boolean closedWithin(Socket socket) {
        try {
            socket.setSoTimeout(READ_TIMEOUT);
            return socket.getInputStream().read() < 0;
        } catch (InterruptedIOException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 写 UTF-8 字符串（带 2 字节长度前缀）。
     *
     * @param out   目标
     * @param value 字符串
     * @throws IOException 写失败
     */
    private static void writeUtf(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.write(bytes);
    }

    /**
     * 写双字节大端整数。
     *
     * @param out   目标
     * @param value 值
     */
    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * 写出一个完整控制包。
     *
     * @param out   输出流
     * @param flags 首字节
     * @param body  包体
     * @throws IOException 写失败
     */
    private static void writeFrame(DataOutputStream out, int flags, ByteArrayOutputStream body) throws IOException {
        byte[] data = body.toByteArray();
        out.write(flags);
        int length = data.length;
        do {
            int digit = length % 128;
            length /= 128;
            out.write(length > 0 ? digit | 0x80 : digit);
        } while (length > 0);
        out.write(data);
        out.flush();
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
     * 内联 MQTT 3.1.1 客户端，仅用于本机回环测试。
     */
    private static final class Client implements AutoCloseable {

        /**
         * 套接字
         */
        private final Socket socket;
        /**
         * 输出
         */
        private final DataOutputStream out;
        /**
         * 输入
         */
        private final DataInputStream in;
        /**
         * CONNACK 返回码
         */
        private int connackCode = -1;
        /**
         * 最近一次订阅应答类型
         */
        private int subscribeAckType = -1;
        /**
         * 最近一次订阅授予 QoS
         */
        private int lastGranted = -1;
        /**
         * 出站报文标识
         */
        private int nextId = 1;

        /**
         * 建立连接。
         *
         * @param port     端口
         * @param clientId 客户端标识
         * @throws IOException 连接失败
         */
        Client(int port, String clientId) throws IOException {
            this(port, clientId, 0x02, null, null, true);
        }

        /**
         * 建立连接，可选携带凭据。
         *
         * @param port     端口
         * @param clientId 客户端标识
         * @param withAuth 是否携带用户名密码
         * @throws IOException 连接失败
         */
        Client(int port, String clientId, boolean withAuth) throws IOException {
            this(port, clientId, withAuth ? 0x02 | 0x40 | 0x80 : 0x02, null, null, withAuth);
        }

        /**
         * 建立连接，可选携带遗嘱。
         *
         * @param port      端口
         * @param clientId  客户端标识
         * @param flags     连接标志
         * @param willTopic 遗嘱主题
         * @param willMsg   遗嘱消息
         * @throws IOException 连接失败
         */
        Client(int port, String clientId, int flags, String willTopic, String willMsg) throws IOException {
            this(port, clientId, flags, willTopic, willMsg, false);
        }

        /**
         * 建立连接。
         *
         * @param port      端口
         * @param clientId  客户端标识
         * @param flags     连接标志
         * @param willTopic 遗嘱主题
         * @param willMsg   遗嘱消息
         * @param withAuth  是否携带用户名密码
         * @throws IOException 连接失败
         */
        Client(int port, String clientId, int flags, String willTopic, String willMsg, boolean withAuth)
                throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setTcpNoDelay(true);
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeUtf(body, "MQTT");
            body.write(4);
            body.write(flags);
            writeShort(body, 60);
            writeUtf(body, clientId);
            if (willTopic != null) {
                writeUtf(body, willTopic);
                writeUtf(body, willMsg);
            }
            if (withAuth) {
                writeUtf(body, "u");
                writeUtf(body, "p");
            }
            frame(0x10, body);
            Packet ack = read(READ_TIMEOUT);
            if (ack != null && ack.type == 2 && ack.raw.length > 1) {
                connackCode = ack.raw[1] & 0xFF;
            }
        }

        /**
         * 断言 CONNACK 已接受。
         */
        void expectConnected() {
            check("CONNACK 接受 (code=" + connackCode + ")", connackCode == 0);
        }

        /**
         * @return 最近一次订阅应答类型
         */
        int subscribeAckType() {
            return subscribeAckType;
        }

        /**
         * 订阅。
         *
         * @param packetId 报文标识
         * @param filter   过滤器
         * @param qos      请求 QoS
         * @throws IOException 写失败
         */
        void subscribe(int packetId, String filter, int qos) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeShort(body, packetId);
            writeUtf(body, filter);
            body.write(qos);
            frame(0x82, body);
            Packet resp = read(READ_TIMEOUT);
            if (resp != null) {
                subscribeAckType = resp.type;
                if (resp.raw.length > 0) {
                    lastGranted = resp.raw[resp.raw.length - 1] & 0xFF;
                }
            }
        }

        /**
         * 发布。
         *
         * @param topic   主题
         * @param payload 载荷
         * @param qos     QoS
         * @throws IOException 写失败
         */
        void publish(String topic, String payload, int qos) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeUtf(body, topic);
            if (qos > 0) {
                writeShort(body, nextId++);
            }
            body.write(payload.getBytes(StandardCharsets.UTF_8));
            frame(0x30 | ((qos & 3) << 1), body);
        }

        /**
         * 主动 DISCONNECT。
         *
         * @throws IOException 写失败
         */
        void disconnect() throws IOException {
            frame(0xE0, new ByteArrayOutputStream());
        }

        /**
         * 写出一个完整控制包。
         *
         * @param flags 首字节
         * @param body  包体
         * @throws IOException 写失败
         */
        void frame(int flags, ByteArrayOutputStream body) throws IOException {
            writeFrame(out, flags, body);
        }

        /**
         * 带超时读取一个报文。
         *
         * @param millis 超时毫秒
         * @return 报文，超时返回空
         * @throws IOException 读失败
         */
        Packet read(int millis) throws IOException {
            socket.setSoTimeout(millis);
            try {
                return readPacket();
            } catch (InterruptedIOException e) {
                return null;
            } finally {
                socket.setSoTimeout(0);
            }
        }

        /**
         * 跳过非 PUBLISH 应答读取下一个 PUBLISH。
         *
         * @param millis 超时毫秒
         * @return PUBLISH 报文，超时返回空
         * @throws IOException 读失败
         */
        Packet readPublish(int millis) throws IOException {
            long deadline = System.currentTimeMillis() + millis;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return null;
                }
                Packet p = read((int) left);
                if (p == null) {
                    return null;
                }
                if (p.type == 3) {
                    return p;
                }
            }
        }

        /**
         * 读取一个报文。
         *
         * @return 报文，流结束返回空
         * @throws IOException 读失败
         */
        Packet readPacket() throws IOException {
            int first = in.read();
            if (first < 0) {
                return null;
            }
            long length = 0;
            int multiplier = 1;
            int digits = 0;
            while (true) {
                int digit = in.readUnsignedByte();
                length += (long) (digit & 0x7F) * multiplier;
                multiplier *= 128;
                if (++digits > 4 || length > 1_000_000L) {
                    throw new IOException("非法剩余长度 " + length);
                }
                if ((digit & 0x80) == 0) {
                    break;
                }
            }
            byte[] raw = new byte[(int) length];
            in.readFully(raw);
            return new Packet(first >> 4, first & 0x0F, raw);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    /**
     * 收到的报文。
     */
    private static final class Packet {

        /**
         * 类型
         */
        final int type;
        /**
         * 标志位
         */
        final int flags;
        /**
         * 原始包体
         */
        final byte[] raw;
        /**
         * QoS
         */
        final int qos;
        /**
         * 主题
         */
        final String topic;
        /**
         * 报文标识
         */
        final int packetId;
        /**
         * 载荷
         */
        final String payload;

        /**
         * 解析报文。
         *
         * @param type  类型
         * @param flags 标志
         * @param raw   包体
         */
        Packet(int type, int flags, byte[] raw) {
            this.type = type;
            this.flags = flags;
            this.raw = raw;
            this.qos = (flags >> 1) & 3;
            String t = "";
            int id = 0;
            String pl = "";
            if (type == 3 && raw.length >= 2) {
                int len = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
                int pos = 2 + len;
                if (pos <= raw.length) {
                    t = new String(raw, 2, len, StandardCharsets.UTF_8);
                    if (qos > 0 && pos + 1 < raw.length) {
                        id = ((raw[pos] & 0xFF) << 8) | (raw[pos + 1] & 0xFF);
                        pos += 2;
                    }
                    pl = new String(raw, pos, raw.length - pos, StandardCharsets.UTF_8);
                }
            } else {
                pl = new String(raw, StandardCharsets.UTF_8);
            }
            this.topic = t;
            this.packetId = id;
            this.payload = pl;
        }

        /**
         * 取包体指定偏移起的双字节整数。
         *
         * @param offset 偏移
         * @return 整数值
         */
        int bodyInt(int offset) {
            return ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
        }
    }
}
