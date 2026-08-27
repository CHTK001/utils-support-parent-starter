package com.chua.common.support.network.ftp;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * FTP 数据通道：被动模式数据连接管理。
 *
 * <p>FTP 数据连接独立于控制连接，用于实际的文件传输。
 * 被动模式下服务器开放随机端口等待客户端连接。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
class FtpDataChannel {

    /**
     * 关联的 FTP 会话
     */
    private final FtpSession session;

    /**
     * 数据端口监听 ServerSocket
     */
    private volatile ServerSocket serverSocket;

    /**
     * 当前数据连接
     */
    private volatile Socket dataSocket;

    /**
     * 被动模式端口范围
     */
    private final int portMin;
    private final int portMax;

    /**
     * 连接超时（毫秒）
     */
    private final int connectTimeoutMs;

    /**
     * 创建数据通道。
     *
     * @param session          关联的 FTP 会话
     * @param portMin          被动模式端口范围起始
     * @param portMax          被动模式端口范围结束
     * @param connectTimeoutMs 连接超时（毫秒）
     */
    FtpDataChannel(FtpSession session, int portMin, int portMax, int connectTimeoutMs) {
        this.session = session;
        this.portMin = portMin;
        this.portMax = portMax;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * 进入被动模式：开放随机端口，返回 PASV 响应。
     *
     * @param serverIp 服务器 IP 地址
     * @return PASV 响应字符串
     * @throws IOException 端口绑定失败
     */
    String enterPassiveMode(String serverIp) throws IOException {
        close();
        // 尝试随机端口
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(serverIp, 0), 50);
        int port = serverSocket.getLocalPort();
        // 格式化：227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
        String ipStr = serverIp.replace('.', ',');
        int p1 = (port >> 8) & 0xFF;
        int p2 = port & 0xFF;
        log.debug("FTP PASV 模式: {}:{} -> port={}", serverIp, port, port);
        return "227 Entering Passive Mode (" + ipStr + "," + p1 + "," + p2 + ")";
    }

    /**
     * 等待客户端数据连接（被动模式）。
     *
     * @return 数据连接 Socket
     * @throws IOException 连接超时或失败
     */
    Socket acceptDataConnection() throws IOException {
        if (serverSocket == null) {
            throw new IOException("PASV 模式未启动");
        }
        dataSocket = serverSocket.accept();
        dataSocket.setSoTimeout(connectTimeoutMs);
        return dataSocket;
    }

    /**
     * 主动模式：服务器主动连接客户端指定的端口。
     *
     * @param clientIp   客户端 IP 地址
     * @param clientPort 客户端端口
     * @return 数据连接 Socket
     * @throws IOException 连接失败
     */
    Socket connectToClient(String clientIp, int clientPort) throws IOException {
        close();
        dataSocket = new Socket(clientIp, clientPort);
        dataSocket.setSoTimeout(connectTimeoutMs);
        return dataSocket;
    }

    /**
     * 获取当前数据连接的输入流。
     *
     * @return 输入流，无连接时返回 null
     */
    InputStream getDataInputStream() {
        Socket s = dataSocket;
        return s != null && !s.isClosed() ? null : null; // 由调用方从 Socket 获取
    }

    /**
     * 关闭数据通道。
     */
    void close() {
        try {
            if (dataSocket != null && !dataSocket.isClosed()) {
                dataSocket.close();
            }
        } catch (IOException ignored) {
        }
        dataSocket = null;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        serverSocket = null;
    }

    /**
     * 获取当前数据连接。
     *
     * @return 数据 Socket
     */
    Socket getDataSocket() {
        return dataSocket;
    }

    /**
     * 是否正在监听连接（PASV 模式已启动）。
     */
    boolean isListening() {
        return serverSocket != null && !serverSocket.isClosed();
    }
}
