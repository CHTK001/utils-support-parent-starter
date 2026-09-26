package com.chua.dingding.support.bot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;

/**
 * 钉钉 Stream 网关模拟器（纯 JDK，无第三方依赖）。
 *
 * <p>同一个端口同时提供 REST 与 WebSocket：REST 侧对未注册路径返回工装本地的
 * {@code 404 {"errcode":404,"errmsg":"no such api on fake dingtalk gateway"}}，
 * WebSocket 侧完成 RFC6455 握手，并按钉钉 Stream 模式的帧契约（specVersion / type /
 * headers / data，其中 data 为 JSON 字符串）与客户端交互。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class FakeDingTalkGateway implements AutoCloseable {

    /**
     * RFC6455 握手魔数。
     */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * 机器人消息回调的订阅主题。
     */
    static final String BOT_MESSAGE_TOPIC = "/v1.0/im/bot/messages/get";

    /**
     * 钉钉 Stream 帧的协议版本。
     */
    private static final String SPEC_VERSION = "1.0";

    /**
     * 钉钉 Stream 帧的内容类型。
     */
    private static final String CONTENT_TYPE = "application/json";

    /**
     * 未注册路径的响应体，仅为工装本地语义，真实平台不会返回该文案。
     */
    private static final String NOT_SUPPORTED =
            "{\"errcode\":404,\"errmsg\":\"no such api on fake dingtalk gateway\"}";

    private final ServerSocket server;

    private final List<HttpCall> httpCalls = new CopyOnWriteArrayList<>();

    private final LinkedBlockingQueue<String> clientFrames = new LinkedBlockingQueue<>();

    private final List<Route> routes = new CopyOnWriteArrayList<>();

    private volatile OutputStream wsOut;

    private volatile boolean wsHandshaked;

    private volatile int closeCode = -1;

    /**
     * 绑定随机本机端口并启动接受线程。
     *
     * @throws IOException 端口绑定失败
     */
    FakeDingTalkGateway() throws IOException {
        server = new ServerSocket(0);
        Thread acceptor = new Thread(this::acceptLoop, "fake-dingtalk-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * HTTP 基础地址，用于注入到被测客户端的 baseUrl。
     *
     * @return http://127.0.0.1:port
     */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /**
     * 本机的 WebSocket 接入地址，作为 /v1.0/gateway/connections/open 的返回值。
     *
     * @return ws://127.0.0.1:port/websocket
     */
    String websocketUrl() {
        return "ws://127.0.0.1:" + server.getLocalPort() + "/websocket";
    }

    /**
     * 注册 REST 路由，按 method + 路径前缀匹配，取最长前缀；
     * 同一 method + 前缀重复注册时后一次覆盖前一次，便于单个用例改判响应。
     *
     * @param method HTTP 方法
     * @param pathPrefix 路径前缀
     * @param status 响应状态码
     * @param bodySupplier 响应体提供者
     */
    void route(String method, String pathPrefix, int status, Responder bodySupplier) {
        routes.removeIf(route -> route.method.equals(method)
                && route.pathPrefix.equals(pathPrefix));
        routes.add(new Route(method, pathPrefix, status, bodySupplier));
    }

    /**
     * @return 已记录的 REST 调用
     */
    List<HttpCall> httpCalls() {
        return httpCalls;
    }

    /**
     * 等待首个匹配指定方法与前缀的 REST 调用。
     *
     * @param method HTTP 方法
     * @param pathPrefix 路径前缀
     * @param timeoutMs 超时毫秒
     * @return 命中的调用，超时返回 null
     */
    HttpCall awaitCall(String method, String pathPrefix, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (HttpCall call : httpCalls) {
                if (call.method.equals(method) && call.path.startsWith(pathPrefix)) {
                    return call;
                }
            }
            sleepQuietly(50L);
        }
        return null;
    }

    /**
     * 阻塞获取一条客户端上行 WS 文本帧。
     *
     * @param timeoutMs 超时毫秒
     * @return 帧内容，超时返回 null
     */
    String pollClientFrame(long timeoutMs) {
        try {
            return clientFrames.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 等待首条满足条件的客户端上行帧。
     *
     * @param match 判定条件
     * @param timeoutMs 超时毫秒
     * @return 命中的帧原文，超时返回 null
     */
    String awaitFrame(java.util.function.Predicate<String> match, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String frame = pollClientFrame(Math.max(50L, deadline - System.currentTimeMillis()));
            if (frame == null) {
                continue;
            }
            if (match.test(frame)) {
                return frame;
            }
            clientFrames.offer(frame);
        }
        return null;
    }

    /**
     * @return 服务端是否已完成 WS 握手
     */
    boolean websocketConnected() {
        return wsHandshaked;
    }

    /**
     * @return 对端关闭码，未关闭返回 -1
     */
    int closeCode() {
        return closeCode;
    }

    /**
     * 下行推送一条文本帧。
     *
     * @param json 帧内容
     */
    void push(String json) {
        try {
            writeFrame(1, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("推送 WS 帧失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下行推送一条原始帧文本，不做任何结构包装。
     *
     * @param rawFrameJson 帧原文
     */
    void pushFrame(String rawFrameJson) {
        push(rawFrameJson);
    }

    /**
     * 下行推送一条机器人消息 CALLBACK 帧，主题固定为 {@link #BOT_MESSAGE_TOPIC}。
     *
     * @param messageId 消息 ID
     * @param dataJson 消息体 JSON
     */
    void pushBotMessage(String messageId, String dataJson) {
        push(buildFrame("CALLBACK", BOT_MESSAGE_TOPIC, messageId, dataJson));
    }

    /**
     * 下行推送一条 SYSTEM 帧，用于 ping 与 disconnect 等平台事件。
     *
     * @param topic 系统事件主题
     * @param messageId 消息 ID
     * @param dataJson 事件体 JSON
     */
    void pushSystemEvent(String topic, String messageId, String dataJson) {
        push(buildFrame("SYSTEM", topic, messageId, dataJson));
    }

    /**
     * 主动关闭 WS 连接。
     *
     * @param code 关闭码
     */
    void closeWebsocket(int code) {
        byte[] payload = new byte[] {(byte) (code >> 8), (byte) code};
        try {
            writeFrame(8, payload);
        } catch (IOException ignored) {
            // 连接已断开
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭本地监听失败无需处理
        }
    }

    /**
     * 接受连接并分派 HTTP / WebSocket。
     */
    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> handle(socket), "fake-dingtalk-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    /**
     * 处理单条连接。
     *
     * @param socket 连接
     */
    private void handle(Socket socket) {
        try (Socket sock = socket) {
            InputStream in = sock.getInputStream();
            Map<String, String> head = readHead(in);
            if (head == null) {
                return;
            }
            String[] line = head.get("__line").split(" ");
            String method = line[0];
            String path = line[1];
            HttpCall call = new HttpCall();
            call.method = method;
            call.path = path;
            call.headers.putAll(head);
            int len = Integer.parseInt(head.getOrDefault("content-length", "0"));
            if (len > 0) {
                call.body = new String(readN(in, len), StandardCharsets.UTF_8);
            }
            httpCalls.add(call);
            boolean upgrade = "websocket".equalsIgnoreCase(head.getOrDefault("upgrade", ""));
            if (upgrade) {
                serveWebsocket(sock, in, head);
                return;
            }
            Reply reply = respond(method, path, call);
            writeHttp(sock.getOutputStream(), reply.status, reply.body);
        } catch (IOException ignored) {
            // 单条连接的异常不影响工装
        }
    }

    /**
     * 按路由表生成响应体。
     *
     * @param method HTTP 方法
     * @param path 请求路径
     * @param call 调用记录
     * @return 响应体
     */
    private Reply respond(String method, String path, HttpCall call) {
        Route best = null;
        for (Route route : routes) {
            if (!route.method.equals(method) || !path.startsWith(route.pathPrefix)) {
                continue;
            }
            if (best == null || route.pathPrefix.length() > best.pathPrefix.length()) {
                best = route;
            }
        }
        return best == null ? new Reply(404, NOT_SUPPORTED) : new Reply(best.status, best.responder.body(call));
    }

    /**
     * 完成 WS 握手并驱动会话。
     *
     * @param sock 连接
     * @param in 输入流
     * @param head 请求头
     * @throws IOException 读写异常
     */
    private void serveWebsocket(Socket sock, InputStream in, Map<String, String> head)
            throws IOException {
        String accept = sha1Base64(head.get("sec-websocket-key") + WS_GUID);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        OutputStream out = sock.getOutputStream();
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        wsOut = out;
        wsHandshaked = true;
        readClientFrames(in);
        wsHandshaked = false;
        wsOut = null;
    }

    /**
     * 持续读取客户端上行帧。
     *
     * @param in 输入流
     * @throws IOException 读异常
     */
    private void readClientFrames(InputStream in) throws IOException {
        StringBuilder pending = new StringBuilder();
        while (true) {
            int b0 = in.read();
            if (b0 < 0) {
                return;
            }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            int b1 = in.read();
            if (b1 < 0) {
                return;
            }
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7F;
            if (length == 126) {
                length = ((long) (in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (length == 127) {
                byte[] wide = readN(in, 8);
                length = 0L;
                for (byte value : wide) {
                    length = (length << 8) | (value & 0xFF);
                }
            }
            byte[] mask = masked ? readN(in, 4) : null;
            byte[] payload = readN(in, (int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            if (opcode == 8) {
                if (payload.length >= 2) {
                    closeCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                }
                return;
            }
            if (opcode == 9) {
                writeFrame(10, payload);
                continue;
            }
            if (opcode == 10) {
                continue;
            }
            if (opcode == 1) {
                pending.append(new String(payload, StandardCharsets.UTF_8));
                if (fin) {
                    String frame = pending.toString();
                    pending.setLength(0);
                    onClientFrame(frame);
                }
            }
        }
    }

    /**
     * 记录客户端上行帧，应答由用例通过 awaitFrame 断言。
     *
     * @param frame 帧原文
     */
    private void onClientFrame(String frame) {
        clientFrames.offer(frame);
    }

    /**
     * 组装钉钉 Stream 帧，data 字段按官方契约是承载业务体的 JSON 字符串而非嵌套对象。
     *
     * @param type 帧类型
     * @param topic 订阅主题
     * @param messageId 消息 ID
     * @param dataJson 业务体 JSON
     * @return 帧文本
     */
    private static String buildFrame(String type, String topic, String messageId, String dataJson) {
        JsonObject headers = JsonObject.of("topic", topic)
                .fluentPut("messageId", messageId)
                .fluentPut("contentType", CONTENT_TYPE);
        JsonObject frame = JsonObject.of("specVersion", SPEC_VERSION)
                .fluentPut("type", type)
                .fluentPut("headers", headers)
                .fluentPut("data", dataJson);
        return Json.toJson(frame);
    }

    /**
     * 下行一个文本 / 关闭帧。
     *
     * @param opcode 操作码
     * @param payload 载荷
     * @throws IOException 写异常
     */
    private synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
        OutputStream out = wsOut;
        if (out == null) {
            throw new IOException("WebSocket 未连接");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(0x80 | opcode);
        int length = payload.length;
        if (length < 126) {
            bos.write(length);
        } else if (length < 65536) {
            bos.write(126);
            bos.write((length >> 8) & 0xFF);
            bos.write(length & 0xFF);
        } else {
            bos.write(127);
            for (int i = 7; i >= 0; i--) {
                bos.write((int) (((long) length >> (8 * i)) & 0xFF));
            }
        }
        bos.write(payload);
        out.write(bos.toByteArray());
        out.flush();
    }

    /**
     * 读取请求行与请求头。
     *
     * @param in 输入流
     * @return 头信息（小写键 + __line 请求行），空连接返回 null
     * @throws IOException 读异常
     */
    private static Map<String, String> readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            int value = in.read();
            if (value < 0) {
                return null;
            }
            bos.write(value);
            byte[] buf = bos.toByteArray();
            int n = buf.length;
            if (n >= 4 && buf[n - 4] == '\r' && buf[n - 3] == '\n'
                    && buf[n - 2] == '\r' && buf[n - 1] == '\n') {
                break;
            }
        }
        String text = bos.toString(StandardCharsets.US_ASCII);
        Map<String, String> head = new java.util.HashMap<>();
        String[] lines = text.split("\r\n");
        head.put("__line", lines[0]);
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx > 0) {
                head.put(lines[i].substring(0, idx).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(idx + 1).trim());
            }
        }
        return head;
    }

    /**
     * 写出定长 JSON 响应并关闭连接。
     *
     * @param out 输出流
     * @param status HTTP 状态码
     * @param body 响应体
     * @throws IOException 写异常
     */
    private static void writeHttp(OutputStream out, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    /**
     * 给出状态码对应的 HTTP 原因短语。
     *
     * @param status 状态码
     * @return 原因短语
     */
    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 500:
                return "Internal Server Error";
            default:
                return "Status";
        }
    }

    /**
     * 精确读取 n 个字节。
     *
     * @param in 输入流
     * @param n 字节数
     * @return 字节数组
     * @throws IOException 读异常
     */
    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read < 0) {
                throw new IOException("连接提前结束");
            }
            off += read;
        }
        return buf;
    }

    /**
     * SHA-1 后 Base64。
     *
     * @param value 输入
     * @return 摘要
     */
    private static String sha1Base64(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder()
                    .encodeToString(digest.digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 静默 sleep。
     *
     * @param millis 毫秒
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 路由响应提供者。
     */
    interface Responder {

        /**
         * 生成响应体。
         *
         * @param call 调用记录
         * @return 响应体 JSON
         */
        String body(HttpCall call);
    }

    /**
     * 路由响应。
     */
    private static final class Reply {

        private final int status;

        private final String body;

        private Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * 路由定义。
     */
    private static final class Route {

        private final String method;

        private final String pathPrefix;

        private final int status;

        private final Responder responder;

        private Route(String method, String pathPrefix, int status, Responder responder) {
            this.method = method;
            this.pathPrefix = pathPrefix;
            this.status = status;
            this.responder = responder;
        }
    }

    /**
     * 一次 REST 调用记录。
     */
    static final class HttpCall {

        /**
         * HTTP 方法。
         */
        String method;

        /**
         * 请求路径。
         */
        String path;

        /**
         * 请求体。
         */
        String body = "";

        /**
         * 请求头（键为小写）。
         */
        Map<String, String> headers = new java.util.HashMap<>();

        /**
         * @return JSON 视图
         */
        Map<?, ?> json() {
            return Json.fromJson(body, Map.class);
        }

        @Override
        public String toString() {
            return method + " " + path + " " + body;
        }
    }
}
