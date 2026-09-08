package com.chua.remote.controller;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 帧 WS 桥（远控画面推流）——内嵌最小 WebSocket 服务器。
 *
 * <p>绕开框架 {@code WebSocketSyncServer} 的连接保活怪癖（客户端连接异常 1006 关闭），
 * 自实现裸 ServerSocket + 手动握手（101）+ 文本帧广播。前端（vue-support-remote-starter
 * 远控页）经 {@code ws://host:port/ws} 连接，payload 为 {@code frame:base64(JPEG)}，
 * 前端解码嗅探 FFD8 后 canvas 直绘。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FrameWsBridge {

    private final int port;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final ScheduledExecutorService broadcaster = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "frame-ws-broadcaster");
        t.setDaemon(true);
        return t;
    });

    public FrameWsBridge(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        acceptThread = new Thread(this::acceptLoop, "frame-ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log.info("帧WS桥已启动 port:{}", port);
    }

    /** 桥端口 */
    public int port() {
        return port;
    }

    /** 接受连接循环 */
    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                clients.add(socket);
                Thread t = new Thread(() -> handleClient(socket), "frame-ws-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break;
                }
                log.warn("接受连接失败", e);
            }
        }
    }

    /** 单客户端处理：握手 + 读循环（保持连接，断连移除） */
    private void handleClient(Socket socket) {
        try {
            if (!performHandshake(socket)) {
                removeClient(socket);
                return;
            }
            log.info("WS 客户端已连接: {}", socket.getRemoteSocketAddress());
            InputStream in = socket.getInputStream();
            while (!socket.isClosed()) {
                // 读帧头首字节（opcode）——阻塞读检测断连（-1 即对端关闭）
                int first = in.read();
                if (first < 0) {
                    break;
                }
                int opcode = first & 0x0F;
                // 忽略非控制帧载荷（纯推流场景——客户端帧在此不处理）
                if (opcode == 0x8) {
                    // 关闭帧——对端主动关闭
                    break;
                }
                if (opcode == 0x9) {
                    // 心跳 PING——按协议回 PONG（0xA）保持连接
                    sendPong(socket);
                }
            }
        } catch (IOException e) {
            // 客户端断连——静默
        } finally {
            removeClient(socket);
        }
    }

    /** 回 PONG（0xA）——响应客户端心跳 PING，防止连接被超时断开 */
    private void sendPong(Socket socket) {
        try {
            synchronized (socket) {
                // 服务端帧不掩码：首字节 0x8A（FIN+0xA），第二字节长度 0（空 PONG）
                socket.getOutputStream().write(new byte[]{(byte) 0x8A, 0x00});
                socket.getOutputStream().flush();
            }
        } catch (IOException e) {
            removeClient(socket);
        }
    }

    /** WebSocket 握手（101 Switching Protocols） */
    private boolean performHandshake(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream reqBuf = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        // 读至头部结束（\r\n\r\n）——浏览器握手请求较大（Origin/UA 等头），单次 read 可能不完整
        while (reqBuf.size() < 16384) {
            int read = in.read(buf);
            if (read < 0) {
                break;
            }
            reqBuf.write(buf, 0, read);
            if (reqBuf.toString(StandardCharsets.UTF_8.name()).contains("\r\n\r\n")) {
                break;
            }
        }
        String request = reqBuf.toString(StandardCharsets.UTF_8.name());
        String key = null;
        for (String line : request.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(":") + 1).trim();
                break;
            }
        }
        if (key == null) {
            return false;
        }
        String accept;
        try {
            accept = computeWebSocketAccept(key);
        } catch (Exception e) {
            throw new IOException("WebSocket 握手失败", e);
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return true;
    }

    /** 计算 WebSocket 接受密钥（SHA-1 + GUID + base64） */
    private static String computeWebSocketAccept(String key) throws Exception {
        String combined = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(combined.getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(digest);
    }

    /**
     * 广播一帧（JPEG 字节）——payload = {@code frame:base64(JPEG)}，前端解码嗅探 FFD8 后直绘。
     *
     * @param jpeg JPEG 帧字节
     */
    public void broadcastFrame(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return;
        }
        String payload = "frame:" + Base64.getEncoder().encodeToString(jpeg);
        byte[] frame = buildTextFrame(payload);
        for (Socket socket : clients) {
            try {
                synchronized (socket) {
                    OutputStream out = socket.getOutputStream();
                    out.write(frame);
                    out.flush();
                }
            } catch (IOException e) {
                removeClient(socket);
            }
        }
    }

    /** 构建 WebSocket 文本帧（服务端无掩码——协议规定） */
    private static byte[] buildTextFrame(String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        if (data.length <= 125) {
            out.write(data.length);
        } else if (data.length <= 65535) {
            out.write(126);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((data.length >> (8 * i)) & 0xFF));
            }
        }
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    private void removeClient(Socket socket) {
        clients.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    /**
     * 周期广播（测试模式）——无真实帧源时以测试 JPEG 占位推流。
     *
     * @param jpeg           测试帧
     * @param intervalMillis 广播间隔（毫秒）
     */
    public void broadcastTestLoop(byte[] jpeg, long intervalMillis) {
        broadcaster.scheduleAtFixedRate(() -> {
            try {
                broadcastFrame(jpeg);
            } catch (Exception e) {
                log.warn("测试帧广播失败", e);
            }
        }, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        broadcaster.shutdownNow();
        for (Socket socket : clients) {
            removeClient(socket);
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    /** 生成 64x48 红色测试 JPEG（仅测试模式用——真实链路由采集→编码产出帧） */
    private static byte[] createTestJpeg() {
        try {
            BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(new java.awt.Color(0xE6, 0x3F, 0x3F));
            g.fillRect(0, 0, 64, 48);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpeg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("测试帧生成失败", e);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        FrameWsBridge bridge = new FrameWsBridge(port);
        bridge.start();
        byte[] testJpeg = createTestJpeg();
        log.info("测试帧 {} 字节，周期广播中（前端 ws://localhost:{}/ws 连接查看）", testJpeg.length, port);
        bridge.broadcastTestLoop(testJpeg, 1000);
        Thread.currentThread().join();
    }
}
