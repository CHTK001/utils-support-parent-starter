package com.chua.gateway.server.bridge;

import com.chua.gateway.server.store.Connection;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;

/**
 * VNC 协议桥接器（主进程内，纯 Java）。
 *
 * <p>浏览器侧使用 noVNC 客户端（WebSocket + RFB 帧）。
 * 协议转换：WebSocket 帧 ↔ raw TCP 字节（VNC RFB 协议）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class NoVncBridge implements RemoteBridge {

    /**
     * VNC 默认端口
     */
    private static final int DEFAULT_VNC_PORT = 5900;

    /**
     * 底层连接
     */
    private final Connection connection;

    /**
     * TCP 套接字（已连接）
     */
    private volatile Socket socket;

    /**
     * 创建 NoVncBridge 实例
     * @param connection connection
     */
    public NoVncBridge(Connection connection) {
        this.connection = connection;
    }

    @Override
    /** Connection */
    public Connection connection() {
        return connection;
    }

    @Override
    /** 连接 */
    public void connect() throws Exception {
        if (isConnected()) {
            return;
        }
        int port = connection.port() > 0 ? connection.port() : DEFAULT_VNC_PORT;
        log.info("[gateway-server] VNC 连接: target={}:{}", connection.host(), port);
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(connection.host(), port), 5000);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        log.info("[gateway-server] VNC 连接建立");
    }

    @Override
    /** 断开 */
    public void disconnect() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                log.warn("[gateway-server] VNC 关闭失败: {}", e.getMessage());
            }
        }
        socket = null;
    }

    @Override
    /** 是否Connected */
    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * 把浏览器侧 WebSocket 帧写入 VNC Server。
     *
     * @param bytes WebSocket 解码后的二进制帧
     * @throws IOException 写入失败
     */
    @Override
    public void writeToRemote(byte[] bytes) throws IOException {
        Objects.requireNonNull(socket, "VNC socket 未连接");
        OutputStream out = socket.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    /**
     * 从 VNC Server 读取一帧（返回时阻塞直到可读）。
     *
     * @return 读取到的字节
     * @throws IOException 读取失败
     */
    @Override
    public byte[] readFromRemote() throws IOException {
        Objects.requireNonNull(socket, "VNC socket 未连接");
        InputStream in = socket.getInputStream();
        byte[] buf = new byte[65536];
        int n = in.read(buf);
        if (n <= 0) {
            throw new IOException("VNC 服务器关闭连接");
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }
}
