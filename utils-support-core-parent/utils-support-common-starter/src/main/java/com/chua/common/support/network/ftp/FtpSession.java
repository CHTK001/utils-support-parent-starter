package com.chua.common.support.network.ftp;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FTP 会话，表示一个已认证的客户端控制连接。
 *
 * <p>每个控制连接一个会话，包含当前工作目录、认证状态、数据通道等。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
class FtpSession {

    /**
     * 会话 ID
     */
    private final String id;

    /**
     * 控制连接 Socket
     */
    private final Socket controlSocket;

    /**
     * 控制连接输出（用于发送 FTP 响应）
     */
    private final PrintWriter controlOut;

    /**
     * 控制连接输入（用于读取 FTP 命令）
     */
    private final BufferedReader controlIn;

    /**
     * FTP 配置
     */
    private final FtpConfig config;

    /**
     * 数据通道（被动/主动模式）
     */
    private final FtpDataChannel dataChannel;

    /**
     * 当前工作目录（相对于 homeDirectory 的路径）
     */
    private volatile String currentDir = "/";

    /**
     * 已认证用户名
     */
    private volatile String username;

    /**
     * 是否已认证
     */
    private volatile boolean authenticated;

    /**
     * 是否匿名用户
     */
    private volatile boolean anonymous;

    /**
     * 二进制传输模式（true）vs ASCII 模式（false）
     */
    private volatile boolean binaryMode = true;

    /**
     * 传输类型：I（二进制）、A（ASCII）
     */
    private volatile char transferType = 'I';

    /**
     * 最后一次传输的偏移量（REST 命令使用）
     */
    private volatile long restartMarker = 0;

    /**
     * 会话是否关闭
     */
    private volatile boolean closed;

    /**
     * 会话属性表（用于存储临时数据，如 RNFR 路径）
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 并发连接计数器
     */
    private static final AtomicInteger SESSION_COUNTER = new AtomicInteger();

    /**
     * 创建 FTP 会话。
     *
     * @param controlSocket 控制连接 Socket
     * @param config        FTP 配置
     * @throws IOException IO 异常
     */
    FtpSession(Socket controlSocket, FtpConfig config) throws IOException {
        this.id = "ftp-" + SESSION_COUNTER.incrementAndGet();
        this.controlSocket = controlSocket;
        this.controlOut = new PrintWriter(
                new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8),
                true);
        this.controlIn = new BufferedReader(
                new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));
        this.config = config;
        this.dataChannel = new FtpDataChannel(this,
                config.getPassivePortMin(), config.getPassivePortMax(),
                config.getDataTimeout() * 1000);
    }

    /**
     * 获取会话 ID。
     */
    String getId() { return id; }

    /**
     * 发送 FTP 响应码 + 消息。
     *
     * @param code    响应码
     * @param message 消息
     */
    void reply(int code, String message) {
        String line = code + " " + message;
        log.debug("FTP SEND [{}]: {}", id, line);
        controlOut.println(line);
    }

    /**
     * 发送多行响应（最后一个响应以空格开头表示结束）。
     *
     * @param code     响应码
     * @param lines    多行内容
     */
    void replyMultiLine(int code, String... lines) {
        for (int i = 0; i < lines.length; i++) {
            String prefix = (i == lines.length - 1) ? " " : "-";
            controlOut.println(code + prefix + lines[i]);
        }
    }

    /**
     * 读取一行 FTP 命令。
     *
     * @return 命令行，null 表示连接关闭
     */
    String readCommand() {
        try {
            return controlIn.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 获取控制连接输入流。
     */
    BufferedReader getControlIn() { return controlIn; }

    /**
     * 获取控制连接输出流。
     */
    PrintWriter getControlOut() { return controlOut; }

    /**
     * 获取 FTP 配置。
     */
    FtpConfig getConfig() { return config; }

    /**
     * 获取数据通道。
     */
    FtpDataChannel getDataChannel() { return dataChannel; }

    /**
     * 获取当前工作目录。
     */
    String getCurrentDir() { return currentDir; }

    /**
     * 设置当前工作目录。
     */
    void setCurrentDir(String dir) { this.currentDir = dir; }

    /**
     * 获取已认证用户名。
     */
    String getUsername() { return username; }

    /**
     * 设置已认证用户名。
     */
    void setUsername(String username) { this.username = username; }

    /**
     * 是否已认证。
     */
    boolean isAuthenticated() { return authenticated; }

    /**
     * 设置认证状态。
     */
    void setAuthenticated(boolean authenticated) { this.authenticated = authenticated; }

    /**
     * 是否匿名用户。
     */
    boolean isAnonymous() { return anonymous; }

    /**
     * 设置匿名用户标志。
     */
    void setAnonymous(boolean anonymous) { this.anonymous = anonymous; }

    /**
     * 是否二进制传输模式。
     */
    boolean isBinaryMode() { return binaryMode; }

    /**
     * 设置二进制传输模式。
     */
    void setBinaryMode(boolean binaryMode) { this.binaryMode = binaryMode; }

    /**
     * 获取传输类型字符。
     */
    char getTransferType() { return transferType; }

    /**
     * 设置传输类型。
     */
    void setTransferType(char transferType) { this.transferType = transferType; }

    /**
     * 获取重启标记偏移量。
     */
    long getRestartMarker() { return restartMarker; }

    /**
     * 设置重启标记偏移量。
     */
    void setRestartMarker(long restartMarker) { this.restartMarker = restartMarker; }

    /**
     * 将客户端相对路径转换为服务器绝对路径。
     *
     * @param path 客户端路径（相对于 homeDirectory）
     * @return 服务器绝对路径
     */
    Path resolvePath(String path) {
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        // 处理相对路径
        if (!path.startsWith("/")) {
            if (currentDir.equals("/")) {
                path = "/" + path;
            } else {
                path = currentDir + "/" + path;
            }
        }
        // 规范化
        try {
            Path base = config.getHomeDirectory().toPath().toAbsolutePath().normalize();
            Path resolved = base.resolve(path.substring(1)).normalize();
            // 安全检查：不允许访问 homeDirectory 之外的路径
            if (!resolved.startsWith(base)) {
                return null; // 路径遍历攻击
            }
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查是否允许执行指定操作。
     */
    boolean canAccess(File file) {
        Path resolved = resolvePath(file.getPath());
        return resolved != null && Files.exists(resolved);
    }

    /**
     * 检查是否允许写入。
     */
    boolean canWrite(Path path) {
        if (anonymous) {
            return config.isAnonymousWriteEnabled();
        }
        // 已认证用户默认允许写入
        return true;
    }

    /**
     * 关闭会话。
     */
    void close() {
        if (closed) return;
        closed = true;
        dataChannel.close();
        try {
            controlSocket.close();
        } catch (IOException ignored) {
        }
        log.debug("FTP 会话关闭: {}", id);
    }

    /**
     * 会话是否已关闭。
     */
    boolean isClosed() { return closed; }

    /**
     * 获取控制连接 Socket。
     */
    Socket getControlSocket() { return controlSocket; }

    /**
     * 设置会话属性。
     *
     * @param key   属性键
     * @param value 属性值
     */
    void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 获取会话属性。
     *
     * @param key 属性键
     * @return 属性值，不存在返回 null
     */
    Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * 移除会话属性。
     *
     * @param key 属性键
     * @return 移除的属性值
     */
    Object removeAttribute(String key) {
        return attributes.remove(key);
    }
}
