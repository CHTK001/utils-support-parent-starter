package com.chua.gateway.server.server;

import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.TunnelRegistry;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket 桥接服务器（独立端口，不依赖 common-starter）。
 *
 * <p>监听 8091 端口，处理 /ws/ssh/{tunnelId} 路径的 WebSocket 升级，
 * 桥接数据到 SSH 目标（通过 TunnelRegistry 查询隧道配置）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WsBridgeServer {

    private static final int PORT = 8092;
    private static final Pattern WS_TUNNEL_PATH = Pattern.compile("/ws/([a-z]+)/([a-f0-9\\-]+)");
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final TunnelRegistry registry;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public WsBridgeServer(TunnelRegistry registry) {
        this.registry = registry;
    }

    public void start() throws Exception {
        serverSocket = new ServerSocket(PORT);
        running = true;
        log.info("[gateway-server] WS 桥接服务器启动: port={}", PORT);
        Thread acceptThread = new Thread(this::acceptLoop, "ws-bridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    private void acceptLoop() {
        while (running) {
            try {
                var socket = serverSocket.accept();
                Thread handler = new Thread(() -> handleConnection(socket), "ws-bridge-" + socket.getPort());
                handler.setDaemon(true);
                handler.start();
            } catch (Exception e) {
                if (running) log.warn("[gateway-server] WS 接受连接失败: {}", e.getMessage());
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            var in = socket.getInputStream();
            var out = socket.getOutputStream();

            // 读取 HTTP 请求
            var requestBytes = new byte[4096];
            int n = in.read(requestBytes);
            var request = new String(requestBytes, 0, n, StandardCharsets.UTF_8);

            // 解析请求行和头
            var lines = request.split("\r\n");
            if (lines.length < 1) { socket.close(); return; }

            var requestLine = lines[0];
            var headers = new java.util.HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                var line = lines[i];
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }

            // 检查 WebSocket 升级
            var upgrade = headers.get("upgrade");
            if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                socket.close();
                return;
            }

            // 解析路径
            var path = requestLine.split(" ")[1];
            var matcher = WS_TUNNEL_PATH.matcher(path);
            if (!matcher.matches()) {
                out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                socket.close();
                return;
            }

            var protocol = matcher.group(1);
            var tunnelId = matcher.group(2);
            var tunnel = registry.get(tunnelId).orElse(null);
            if (tunnel == null) {
                out.write("HTTP/1.1 404 Tunnel Not Found\r\n\r\n".getBytes());
                socket.close();
                return;
            }

            var conn = tunnel.connection();
            var key = headers.get("sec-websocket-key");
            if (key == null) { socket.close(); return; }

            // 计算 Sec-WebSocket-Accept
            var sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(key.getBytes(StandardCharsets.UTF_8));
            sha1.update(WS_MAGIC.getBytes(StandardCharsets.UTF_8));
            var accept = Base64.getEncoder().encodeToString(sha1.digest());

            // 发送 101 响应
            var response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();

            log.info("[gateway-server] WS 握手成功: tunnelId={}", tunnelId);

            // 建立到目标主机的 TCP 连接（echo 测试 / SSH 原生隧道）
            var targetSocket = new Socket();
            targetSocket.connect(new java.net.InetSocketAddress(conn.host(), conn.port()), 10000);
            var targetIn = targetSocket.getInputStream();
            var targetOut = targetSocket.getOutputStream();

            // 发送欢迎消息
            sendWsFrame(out, (byte) 0x01, ("已连接 " + conn.host() + ":" + conn.port() + "\r\n").getBytes());

            // 读取目标输出 → WS 客户端
            Thread targetReader = new Thread(() -> {
                try {
                    var buf = new byte[8192];
                    int len;
                    while ((len = targetIn.read(buf)) != -1) {
                        var data = new byte[len];
                        System.arraycopy(buf, 0, data, 0, len);
                        sendWsFrame(out, (byte) 0x02, data);
                    }
                } catch (Exception ignored) {}
            }, "ws-bridge-target-reader-" + tunnelId);
            targetReader.setDaemon(true);
            targetReader.start();

            // 读取 WS 客户端 → 目标
            try {
                while (true) {
                    var frame = readWsFrame(in);
                    if (frame == null) break;
                    if (frame.opcode == 0x08) break; // Close
                    if (frame.opcode == 0x09) continue; // Ping
                    if (frame.opcode == 0x0A) continue; // Pong
                    if (frame.payload != null && frame.payload.length > 0) {
                        targetOut.write(frame.payload);
                        targetOut.flush();
                    }
                }
            } catch (Exception ignored) {}

            // 清理
            try { targetSocket.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}

        } catch (Exception e) {
            log.warn("[gateway-server] WS 处理异常: {}", e.getMessage());
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // WebSocket 帧读取
    static class WsFrame {
        byte opcode;
        byte[] payload;
    }

    private WsFrame readWsFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;

        var opcode = (byte) (b0 & 0x0F);
        var masked = (b1 & 0x80) != 0;
        var len = b1 & 0x7F;

        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            long l = 0;
            for (int i = 0; i < 8; i++) l = (l << 8) | in.read();
            len = (int) l;
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            in.read(maskKey);
        }

        var frame = new WsFrame();
        frame.opcode = opcode;
        frame.payload = new byte[len];
        in.read(frame.payload);

        if (masked && maskKey != null) {
            for (int i = 0; i < len; i++) {
                frame.payload[i] ^= maskKey[i & 3];
            }
        }

        return frame;
    }

    // WebSocket 帧写入（服务端不发 masked 帧）
    private void sendWsFrame(OutputStream out, byte opcode, byte[] data) throws Exception {
        int b0 = 0x80 | opcode; // FIN + opcode
        out.write(b0);

        if (data.length < 126) {
            out.write(data.length);
        } else if (data.length < 65536) {
            out.write(126);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((data.length >> (i * 8)) & 0xFF));
            }
        }

        out.write(data);
        out.flush();
    }
}