package com.chua.gateway.server.ws;

import com.chua.gateway.server.bridge.GuacamoleBridge;
import com.chua.gateway.server.bridge.RemoteBridge;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import com.chua.gateway.server.tunnel.TunnelRegistry;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket ↔ RemoteBridge 双向帧透传处理器。
 *
 * <p>实现 RFC 6455 最小子集：
 *   <ul>
 *     <li>服务端不解析 client 帧握手 —— 接受 text（opcode 0x1）和 binary（opcode 0x2）</li>
 *     <li>text 帧：JSON {@code {"action":"bind","tunnelId":"xxx"}} 绑定 tunnel</li>
 *     <li>binary 帧：直接写入对应 bridge</li>
 *     <li>服务端启动后台线程从 bridge 读 frame，写回 client（opcode 0x2）</li>
 *   </ul>
 * </p>
 *
 * <p>不处理 ping/pong、fragment、mask（浏览器自动 mask）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WsEndpointHandler {

    /**
     * WebSocket GUID（RFC 6455 §1.3）
     */
    private static final String WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * 帧头读取 buffer
     */
    private static final int HEADER_BUF_SIZE = 14;

    /**
     * 接收帧大小上限（16 MB — 足够 RFB/Guacamole 帧）
     */
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /**
     * Tunnel registry（按 tunnelId 找 GatewayTunnel / bridge）
     */
    private final TunnelRegistry tunnelRegistry;

    /**
     * 后台读写线程池
     */
    private final ExecutorService bridgeExecutor;

    /**
     * 累计处理连接数（监控）
     */
    private final AtomicLong connectionCount = new AtomicLong();

    /**
     * 创建 WsEndpointHandler 实例
     * @param tunnelRegistry tunnelRegistry
     */
    public WsEndpointHandler(TunnelRegistry tunnelRegistry) {
        this.tunnelRegistry = tunnelRegistry;
        this.bridgeExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ws-bridge-io");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 处理一个已 accept 但未握手完成的 client socket。
     *
     * <p>本方法在 {@link GatewayWsServer} acceptLoop 内调用，负责：
     *   <ol>
     *     <li>读取 HTTP 头并完成 RFC 6455 握手</li>
     *     <li>绑定 tunnel（首条 text 消息含 tunnelId）</li>
     *     <li>进入帧循环：client→bridge / bridge→client 双向</li>
     *   </ol>
     * </p>
     *
     * @param socket client socket
     */
    public void handle(Socket socket) {
        long connId = connectionCount.incrementAndGet();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // 1. RFC 6455 握手
            Map<String, String> headers = readHttpHeaders(in);
            String wsKey = headers.get("sec-websocket-key");
            String path = headers.getOrDefault("path", "/");
            if (wsKey == null) {
                sendHttpError(out, 400, "Missing Sec-WebSocket-Key");
                socket.close();
                return;
            }
            String accept = computeAccept(wsKey);
            // guacamole-common-js 的 WebSocketTunnel 会发 Sec-WebSocket-Protocol: guacamole，
            // 服务端必须回同值子协议，否则浏览器拒绝握手。
            String handshake = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    + "Sec-WebSocket-Protocol: guacamole\r\n\r\n";
            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            log.info("[ws] 握手完成 conn={} path={} peer={}", connId, path, socket.getRemoteSocketAddress());

            // 2. 解析 tunnelId：优先从 path 读取（/ws/SSH/{tunnelId}），
            //    兼容 guacamole-common-js / 裸 xterm 客户端；无 path 时回退读首帧 bind JSON。
            String tunnelId = parseTunnelIdFromPath(path);
            System.out.println("[ws] path=" + path + " parsedTunnelId=" + tunnelId);
            if (tunnelId == null) {
                Frame bindFrame = readFrame(in);
                tunnelId = parseTunnelId(bindFrame);
                if (tunnelId == null) {
                    sendCloseFrame(out, 1008, "missing tunnelId");
                    socket.close();
                    return;
                }
            }
            GatewayTunnel tunnel = tunnelRegistry.get(tunnelId).orElse(null);
            if (tunnel == null || tunnel.bridge() == null) {
                sendTextFrame(out, "{\"error\":\"unknown tunnelId\"}");
                sendCloseFrame(out, 1008, "unknown tunnelId");
                socket.close();
                return;
            }
            RemoteBridge bridge = tunnel.bridge();
            if (!bridge.isConnected()) {
                try {
                    bridge.connect();
                } catch (Exception ex) {
                    sendTextFrame(out, "{\"error\":\"bridge connect failed: " + ex.getMessage() + "\"}");
                    sendCloseFrame(out, 1011, "bridge connect failed");
                    socket.close();
                    return;
                }
            }
            // 若 bridge 是 GuacamoleBridge，先发 select 指令建立 RDP/VNC/SSH 会话
            if (bridge instanceof GuacamoleBridge guacBridge) {
                try {
                    guacBridge.writeSelectInstruction();
                    // 网关侧自动完成 size+connect 握手（guacamole-common-js 的 Client
                    // 不自动处理 args，需应用层填参数；这里由网关直接完成，浏览器只需
                    // client.connect() 建立 WS 并接收 ready 与后续数据流）。
                    guacBridge.autoConnect();
                } catch (Exception ex) {
                    sendTextFrame(out, "{\"error\":\"guacd select failed: " + ex.getMessage() + "\"}");
                    sendCloseFrame(out, 1011, "guacd select failed");
                    socket.close();
                    return;
                }
            }
            // 仅当客户端未声明 guacamole 子协议时才发送 bound JSON。
            // guacamole-common-js 的 WebSocketTunnel 要求每条 WS 消息都是合法的
            // guacamole 指令（长度前缀 .value,...），收到不含 "." 的 JSON bound
            // 会触发 close_tunnel(SERVER_ERROR) 导致连接立即关闭，后续 ready/sync
            // 全部丢失。浏览器侧由 guacd 直接发 ready 进入数据流。
            String subprotocol = headers.get("sec-websocket-protocol");
            boolean isGuacamoleClient = subprotocol != null
                    && subprotocol.toLowerCase().contains("guacamole");
            if (!isGuacamoleClient) {
                sendTextFrame(out, "{\"action\":\"bound\",\"tunnelId\":\"" + tunnelId + "\"}");
            }
            log.info("[ws] tunnel 绑定 conn={} id={} protocol={} guacClient={}",
                    connId, tunnelId, tunnel.connection().protocol(), isGuacamoleClient);

            // 3. 启动 server→client 泵线程
            final String boundTunnelId = tunnelId;
            Thread bridgeReader = new Thread(() -> pumpBridgeToClient(bridge, out, connId, boundTunnelId),
                    "ws-bridge-reader-" + connId);
            bridgeReader.setDaemon(true);
            bridgeReader.start();

            // 4. client→bridge 循环（主线程）
            try {
                while (!socket.isClosed()) {
                    Frame f = readFrame(in);
                    log.debug("[ws] recv frame conn={} id={} opcode=0x{} fin={} len={}",
                            connId, tunnelId, Integer.toHexString(f.opcode), f.fin, f.payload.length);
                    if (f.opcode == 0x8) {
                        log.info("[ws] client close conn={} id={}", connId, tunnelId);
                        break;
                    }
                    if (f.opcode == 0x9) {
                        // 服务端→客户端 pong 不 mask（RFC 6455 §5.1）
                        writeFrame(out, 0xA, f.payload, false);
                        continue;
                    }
                    if (f.opcode == 0x2 || f.opcode == 0x1) {
                        try {
                            bridge.writeToRemote(f.payload);
                            log.debug("[ws] bridge.writeToRemote conn={} id={} bytes={}",
                                    connId, tunnelId, f.payload.length);
                        } catch (Exception ex) {
                            log.warn("[ws] bridge write 失败 conn={} id={} err={}", connId, tunnelId, ex.getMessage());
                            break;
                        }
                    }
                    // text frames after bind are ignored (or could be JSON control)
                }
            } finally {
                bridgeReader.interrupt();
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("[ws] conn={} 异常: {}", connId, e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 后台线程：持续从 bridge 读取帧，写回 client。
     */
    private void pumpBridgeToClient(RemoteBridge bridge, OutputStream out, long connId, String tunnelId) {
        log.debug("[ws] pump 启动 conn={} id={}", connId, tunnelId);
        System.out.println("[ws-pump] started conn=" + connId + " id=" + tunnelId);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] frame;
                try {
                    frame = bridge.readFromRemote();
                    System.out.println("[ws-pump] read " + (frame == null ? "null" : frame.length + " bytes") + " conn=" + connId);
                } catch (Exception ex) {
                    log.info("[ws] bridge EOF conn={} id={}: {}", connId, tunnelId, ex.getMessage());
                    System.out.println("[ws-pump] bridge EOF conn=" + connId + " ex=" + ex.getMessage());
                    try {
                        sendCloseFrame(out, 1001, "bridge closed");
                    } catch (Exception ignored) {
                    }
                    return;
                }
                if (frame == null || frame.length == 0) {
                    continue;
                }
                synchronized (out) {
                    // 服务端→客户端必须用文本帧（opcode 0x1）：
                    // guacamole-common-js 的 WebSocketTunnel.onmessage 直接把 event.data
                    // 当 string 做 indexOf/substring 解析指令（含 base64 blob 均为 ASCII 文本），
                    // 若用二进制帧(0x2)浏览器拿到的是 Blob 对象，解析必然失败。
                    writeFrame(out, 0x1, frame, false);
                }
            }
        } catch (Exception e) {
            log.debug("[ws] pumpBridgeToClient conn={} 结束: {}", connId, e.getMessage());
            System.out.println("[ws-pump] exit conn=" + connId + " ex=" + e.getMessage());
        }
    }

    /**
     * 读取 HTTP 请求头直到空行。
     *
     * @param in 输入流
     * @return headers（key 小写）+ path 注入 "path" key
     * @throws IOException 读取失败
     */
    private static Map<String, String> readHttpHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line = readLine(in);
        if (line == null) {
            throw new IOException("empty request");
        }
        // 请求行: GET /path HTTP/1.1
        String[] parts = line.split(" ");
        if (parts.length >= 2) {
            headers.put("method", parts[0]);
            headers.put("path", parts[1]);
        }
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String k = line.substring(0, colon).trim().toLowerCase();
                String v = line.substring(colon + 1).trim();
                headers.put(k, v);
            }
        }
        return headers;
    }

    /**
     * 读取一行 HTTP 头（CRLF 终止）。
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int prev = -1, cur;
        while ((cur = in.read()) != -1) {
            if (cur == '\n' && prev == '\r') {
                byte[] data = baos.toByteArray();
                // strip trailing \r
                int end = data.length - 1;
                return new String(data, 0, end, StandardCharsets.US_ASCII);
            }
            baos.write(cur);
            prev = cur;
            if (baos.size() > 8192) {
                throw new IOException("header too long");
            }
        }
        return null;
    }

    /**
     * 计算 Sec-WebSocket-Accept（RFC 6455 §4.2.2）。
     */
    private static String computeAccept(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((key + WS_MAGIC_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 写 HTTP 错误响应并关闭。
     */
    private static void sendHttpError(OutputStream out, int code, String reason) {
        try {
            String resp = "HTTP/1.1 " + code + " " + reason + "\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    /**
     * 写入一个 WebSocket 帧。
     *
     * @param out     输出流
     * @param opcode  操作码（0x1 text / 0x2 binary / 0x8 close / 0x9 ping / 0xA pong）
     * @param payload 载荷
     * @param mask    服务端→客户端不应 mask，传 false
     * @throws IOException 写入失败
     */
    public static void writeFrame(OutputStream out, int opcode, byte[] payload, boolean mask) throws IOException {
        int len = payload == null ? 0 : payload.length;
        int b1 = 0x80 | (opcode & 0x0F);
        out.write(b1);
        if (len < 126) {
            out.write(mask ? 0x80 | len : len);
        } else if (len < 65536) {
            out.write(mask ? 0x80 | 126 : 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(mask ? 0x80 | 127 : 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >>> (8 * i)) & 0xFF));
            }
        }
        if (mask) {
            byte[] maskKey = new byte[4];
            // simple random mask — fine for server→client but browsers usually ignore mask
            java.util.concurrent.ThreadLocalRandom.current().nextBytes(maskKey);
            out.write(maskKey);
            byte[] masked = new byte[len];
            for (int i = 0; i < len; i++) {
                masked[i] = (byte) (payload[i] ^ maskKey[i & 3]);
            }
            out.write(masked);
        } else if (payload != null && len > 0) {
            out.write(payload);
        }
        out.flush();
    }

    /**
     * 写一条 text 帧（客户端可读 JSON）。
     */
    private static void sendTextFrame(OutputStream out, String text) {
        try {
            writeFrame(out, 0x1, text.getBytes(StandardCharsets.UTF_8), false);
        } catch (Exception ignored) {
        }
    }

    /**
     * 写 close 帧（优雅关闭）。
     */
    private static void sendCloseFrame(OutputStream out, int code, String reason) {
        try {
            byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + reasonBytes.length];
            payload[0] = (byte) ((code >>> 8) & 0xFF);
            payload[1] = (byte) (code & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
            writeFrame(out, 0x8, payload, false);
        } catch (Exception ignored) {
        }
    }

    /**
     * 读取一帧（RFC 6455 §5.2 — 客户端→服务端 必须 mask）。
     */
    private static Frame readFrame(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 == -1 || b2 == -1) {
            throw new IOException("eof");
        }
        boolean fin = (b1 & 0x80) != 0;
        int opcode = b1 & 0x0F;
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        if (len == 126) {
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | (in.read() & 0xFF);
            }
        }
        if (len > MAX_FRAME_BYTES) {
            throw new IOException("frame too large: " + len);
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            for (int i = 0; i < 4; i++) {
                int c = in.read();
                if (c == -1) {
                    throw new IOException("eof in mask");
                }
                mask[i] = (byte) c;
            }
        }
        byte[] payload = new byte[(int) len];
        int total = 0;
        while (total < len) {
            int n = in.read(payload, total, (int) (len - total));
            if (n == -1) {
                throw new IOException("eof in payload");
            }
            total += n;
        }
        if (masked && mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i & 3]);
            }
        }
        return new Frame(fin, opcode, payload);
    }

    /**
     * 从 WS 路径 {@code /ws/<protocol>/<tunnelId>} 解析 tunnelId。
     *
     * @param path 请求路径
     * @return tunnelId；无法解析返回 {@code null}
     */
    private static String parseTunnelIdFromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        // 剥离 query string（guacamole-common-js 的 WebSocketTunnel 会在 URL 后拼 ?undefined）
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String[] parts = path.split("/");
        if (parts.length < 3) {
            return null;
        }
        String candidate = parts[parts.length - 1];
        return candidate.isEmpty() ? null : candidate;
    }

    /**
     * 从 bind text 帧 JSON 中解析 tunnelId（简单匹配，不依赖 Jackson）。
     */
    private static String parseTunnelId(Frame frame) {
        if (frame.opcode != 0x1) {
            return null;
        }
        String s = new String(frame.payload, StandardCharsets.UTF_8);
        int idx = s.indexOf("\"tunnelId\"");
        if (idx < 0) {
            return null;
        }
        int colon = s.indexOf(':', idx);
        int quote1 = s.indexOf('"', colon + 1);
        int quote2 = s.indexOf('"', quote1 + 1);
        if (quote1 < 0 || quote2 < 0) {
            return null;
        }
        return s.substring(quote1 + 1, quote2);
    }

    /**
     * 关闭 IO 线程池。
     */
    public void shutdown() {
        bridgeExecutor.shutdownNow();
    }

    /**
     * 已处理连接总数（监控）。
     *
     * @return 累计值
     */
    public long connectionCount() {
        return connectionCount.get();
    }

    /**
     * 简单帧 POJO（不在外部暴露）。
     */
    private static final class Frame {
        @SuppressWarnings("unused")
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload == null ? new byte[0] : payload;
        }
    }
}
