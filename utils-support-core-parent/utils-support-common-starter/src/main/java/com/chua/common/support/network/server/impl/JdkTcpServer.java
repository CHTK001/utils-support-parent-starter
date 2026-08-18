package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 JDK ServerSocket 的 TCP 服务器实现。
 *
 * <p>同步阻塞模型，每个客户端连接使用独立线程处理。
 * 支持 ServerHandler 注册、粘包处理和 TcpMethod 协议解析。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 基础用法
 * JdkTcpServer server = new JdkTcpServer(setting)
 *         .registerHandler("*", (in, out) -> {
 *             byte[] buffer = new byte[1024];
 *             int read = in.read(buffer);
 *             if (read > 0) {
 *                 out.write(("echo:" + new String(buffer, 0, read)).getBytes());
 *                 out.flush();
 *             }
 *         });
 *
 * // 粘包处理 - 固定长度协议
 * JdkTcpServer server = new JdkTcpServer(setting)
 *         .setPacketLength(10)  // 每条消息固定 10 字节
 *         .registerHandler("*", new TcpMethod() {
 *             @Override
 *             public void handle(InputStream in, OutputStream out) throws Exception {
 *                 // 自动处理粘包，每次调用 receive 返回完整一条消息
 *                 byte[] msg = receive(in, 10);
 *                 if (msg != null) {
 *                     out.write(msg);
 *                     out.flush();
 *                 }
 *             }
 *         });
 *
 * // 粘包处理 - 指定长度字段位置
 * JdkTcpServer server = new JdkTcpServer(setting)
 *         .setLengthFieldOffset(0)   // 长度字段在第 0 个字节
 *         .setLengthFieldLength(2)   // 长度字段占 2 字节
 *         .setLengthAdjustment(0)    // 长度调整值
 *         .registerHandler("*", new TcpMethod() {
 *             @Override
 *             public void handle(InputStream in, OutputStream out) throws Exception {
 *                 byte[] msg = receiveFrame(in);
 *                 if (msg != null) {
 *                     out.write(msg);
 *                     out.flush();
 *                 }
 *             }
 *         });
 * }</pre>
 *
 * @author CH
 * @since 2026/07/26
 */
@Slf4j
@Spi({"jdk-tcp"})
public class JdkTcpServer extends AbstractServer {

    /** 服务器Socket */
    private ServerSocket serverSocket;
    /** Worker池 */
    private ExecutorService workerPool;
    private final Map<String, TcpHandler> handlers = new ConcurrentHashMap<>();

    /**
     * 粘包处理配置：是否启用固定长度帧解析
     */
    private boolean fixedLengthEnabled = false;

    /**
     * 固定长度帧大小（字节）
     */
    private int fixedLength = 0;

    /**
     * 长度字段偏移量（从 0 开始）
     */
    private int lengthFieldOffset = 0;

    /**
     * 长度字段占用字节数（1, 2, 4, 8）
     */
    private int lengthFieldLength = 4;

    /**
     * 长度调整值（帧长度 = 读取的长度 + 此值）
     */
    private int lengthAdjustment = 0;

    /**
     * 初始跳过字节数
     */
    private int lengthIncludesHeaderCount = 1;

    public JdkTcpServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 设置固定长度帧解析。
     *
     * @param length 每条消息的固定字节数
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setPacketLength(int length) {
        this.fixedLengthEnabled = true;
        this.fixedLength = length;
        return this;
    }

    /**
     * 设置长度字段偏移量。
     *
     * @param offset 长度字段在帧中的起始位置（从 0 开始）
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setLengthFieldOffset(int offset) {
        this.lengthFieldOffset = offset;
        return this;
    }

    /**
     * 设置长度字段占用字节数。
     *
     * @param length 长度字段字节数（1, 2, 4, 8）
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setLengthFieldLength(int length) {
        this.lengthFieldLength = length;
        return this;
    }

    /**
     * 设置长度调整值。
     *
     * @param adjustment 帧长度 = 读取的长度 + 此值
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setLengthAdjustment(int adjustment) {
        this.lengthAdjustment = adjustment;
        return this;
    }

    /**
     * 设置初始跳过字节数。
     *
     * @param count 初始跳过的字节数
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer setLengthIncludesHeaderCount(int count) {
        this.lengthIncludesHeaderCount = count;
        return this;
    }

    @Override
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(setting.isSoReuseAddr());
            serverSocket.setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384));
            // backlog 下限 65536:瞬间并发连接(万级突发)下避免内核 accept 队列溢出导致连接被拒
            serverSocket.bind(addr, Math.max(setting.getBacklog(), 65536));
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(serverSocket.getLocalPort());
            workerPool = Executors.newVirtualThreadPerTaskExecutor();
            running = true;

            // 多 acceptor:多个虚拟线程同时阻塞在 accept() 上,内核唤醒后负载分散到各线程,
            // 消除单 accept 线程在高并发连接接纳(每秒万级新建连接)下的调度瓶颈;
            // 同一 ServerSocket 多线程 accept 是 JDK 官方支持的用法(Windows 同样适用)。
            // 默认至少 min(CPU,4) 个 acceptor,支撑百万级连接建立
            int acceptors = Math.max(setting.getBossThreads(),
                    Math.min(Runtime.getRuntime().availableProcessors(), 4));
            for (int i = 0; i < acceptors; i++) {
                workerPool.submit(this::acceptLoop);
            }
            log.info("JDK TcpServer started on {}:{} (backlog={}, acceptors={}, virtualThreads=true)",
                    setting.getHost(), setting.getPort(), Math.max(setting.getBacklog(), 2048), acceptors);
        } catch (IOException e) {
            throw new RuntimeException("TCP 服务器启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            log.info("JDK TcpServer stopped");
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                // 热路径只做 accept+submit:SO 设置(setTcpNoDelay/RCVBUF/SNDBUF)移到
                // handleConnection(虚拟线程)内执行,提升瞬时连接接纳能力
                try {
                    workerPool.submit(() -> handleConnection(socket));
                } catch (Exception e) {
                    log.warn("TCP 任务被拒绝: {}", e.getMessage());
                    try { socket.close(); } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                if (running) {
                    log.error("接受连接异常", e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setTcpNoDelay(setting.isTcpNoDelay());
            // 收发缓冲对齐内核:放大 SO_RCVBUF/SO_SNDBUF 减少高并发下的小包分片与 ACK 往返
            socket.setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384));
            socket.setSendBufferSize(Math.max(setting.getBufferSize(), 16384));
        } catch (IOException ignored) {
        }
        String clientKey = socket.getRemoteSocketAddress().toString();
        log.debug("TCP 连接: {}", clientKey);

        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            TcpHandler handler = findHandler(clientKey);
            if (handler != null) {
                if (fixedLengthEnabled && handler instanceof TcpMethod) {
                    ((TcpMethod) handler).handle(in, out);
                } else {
                    handler.handle(in, out);
                }
            } else {
                // 默认处理：回显
                // 缓冲跟随 setting.bufferSize(autoConfig 在内存充足时设为 16KB),复用而非每连接新建;
                // 高频小请求场景的 flush 为无操作,吞吐由虚拟线程调度主导
                byte[] buffer = new byte[Math.max(setting.getBufferSize(), 16384)];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            log.debug("TCP 连接处理异常: {}", e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private TcpHandler findHandler(String clientKey) {
        TcpHandler handler = handlers.get(clientKey);
        if (handler != null) {
            return handler;
        }
        for (Map.Entry<String, TcpHandler> entry : handlers.entrySet()) {
            if ("*".equals(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 注册 TCP 处理器。
     *
     * @param name    处理器名称（"*" 表示匹配所有连接）
     * @param handler 处理器
     * @return 当前服务器实例，支持链式调用
     */
    public JdkTcpServer registerHandler(String name, TcpHandler handler) {
        handlers.put(name, handler);
        return this;
    }

    /**
     * 接收固定长度的数据帧（处理粘包）。
     *
     * @param in   输入流
     * @param size 期望的帧大小
     * @return 完整的帧数据，如果流结束返回 null
     * @throws IOException IO 异常
     */
    public static byte[] receiveFrame(InputStream in, int size) throws IOException {
        byte[] frame = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = in.read(frame, offset, size - offset);
            if (read == -1) {
                return null;
            }
            offset += read;
        }
        return frame;
    }

    /**
     * 静态重载：使用显式配置参数接收带长度字段的帧（接口默认方法可用）。
     *
     * @param in                       输入流
     * @param lengthFieldOffset        长度字段偏移
     * @param lengthFieldLength        长度字段长度
     * @param lengthAdjustment         长度调整
     * @param lengthIncludesHeaderCount 是否包含头部长度（1/0）
     * @return 完整的帧数据
     * @throws IOException IO 异常
     */
    public static byte[] receiveFrame(InputStream in, int lengthFieldOffset, int lengthFieldLength,
                                       int lengthAdjustment, int lengthIncludesHeaderCount) throws IOException {
        byte[] header = new byte[lengthFieldOffset + lengthFieldLength];
        int offset = 0;
        while (offset < header.length) {
            int read = in.read(header, offset, header.length - offset);
            if (read == -1) {
                return null;
            }
            offset += read;
        }

        int frameLength = parseLength(header, lengthFieldOffset, lengthFieldLength);
        frameLength += lengthAdjustment;

        int dataLength = frameLength - lengthIncludesHeaderCount;
        if (dataLength <= 0) {
            return null;
        }

        byte[] data = new byte[dataLength];
        int read = in.read(data);
        while (read < dataLength) {
            int n = in.read(data, read, dataLength - read);
            if (n == -1) {
                break;
            }
            read += n;
        }
        if (lengthIncludesHeaderCount == 1) {
            byte[] full = new byte[header.length + data.length];
            System.arraycopy(header, 0, full, 0, header.length);
            System.arraycopy(data, 0, full, header.length, data.length);
            return full;
        }
        return data;
    }

    /**
     * 接收带长度字段的帧（处理粘包）。
     *
     * @param in 输入流
     * @return 完整的帧数据，如果流结束返回 null
     * @throws IOException IO 异常
     */
    public byte[] receiveFrame(InputStream in) throws IOException {
        // 读取长度字段
        int headerSize = lengthIncludesHeaderCount;
        byte[] header = new byte[lengthFieldOffset + lengthFieldLength];
        int offset = 0;
        while (offset < header.length) {
            int read = in.read(header, offset, header.length - offset);
            if (read == -1) return null;
            offset += read;
        }

        // 解析长度字段
        int frameLength = parseLength(header, lengthFieldOffset, lengthFieldLength);
        frameLength += lengthAdjustment;

        // 读取帧数据（减去头部）
        int dataLength = frameLength - headerSize;
        if (dataLength <= 0) {
            return null;
        }

        byte[] data = new byte[dataLength];
        offset = 0;
        while (offset < dataLength) {
            int read = in.read(data, offset, dataLength - offset);
            if (read == -1) return null;
            offset += read;
        }

        // 返回完整帧（含头部）
        ByteBuffer buffer = ByteBuffer.allocate(headerSize + dataLength);
        buffer.put(header);
        buffer.put(data);
        return buffer.array();
    }

    /**
     * 解析长度字段。
     *
     * @param bytes       包含长度字节的数组
     * @param offset      长度字段偏移量
     * @param fieldLength 长度字段字节数
     * @return 解析出的长度值
     */
    private static int parseLength(byte[] bytes, int offset, int fieldLength) {
        switch (fieldLength) {
            case 1:
                return bytes[offset] & 0xFF;
            case 2:
                return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
            case 4:
                return ((bytes[offset] & 0xFF) << 24) |
                        ((bytes[offset + 1] & 0xFF) << 16) |
                        ((bytes[offset + 2] & 0xFF) << 8) |
                        (bytes[offset + 3] & 0xFF);
            default:
                throw new IllegalArgumentException("Unsupported length field length: " + fieldLength);
        }
    }

    /**
     * 注册 TCP 处理器（链式调用）。
     *
     * @param handler 处理器
     * @return 当前服务器实例
     */
    public JdkTcpServer handler(TcpHandler handler) {
        return registerHandler("*", handler);
    }

    /**
     * TCP 处理器接口。
     */
    @FunctionalInterface
    public interface TcpHandler {
        /**
         * 处理 TCP 连接。
         *
         * @param in  输入流
         * @param out 输出流
         */
        void handle(InputStream in, OutputStream out) throws Exception;
    }

    /**
     * TCP 帧处理方法（支持粘包处理）。
     *
     * <p>当启用了粘包处理时，使用此接口代替 TcpHandler。
     * 可以通过 {@link #receiveFrame} 或 {@link #receiveFrame(InputStream, int)} 方法获取完整的数据帧。</p>
     *
     * <h3>示例：固定长度协议</h3>
     * <pre>{@code
     * server.setPacketLength(10)
     *       .handler(new TcpMethod() {
     *           @Override
     *           public void handle(InputStream in, OutputStream out) throws Exception {
     *               byte[] msg;
     *               while ((msg = receiveFrame(in, 10)) != null) {
     *                   out.write(msg);
     *                   out.flush();
     *               }
     *           }
     *       });
     * }</pre>
     *
     * <h3>示例：长度字段协议</h3>
     * <pre>{@code
     * server.setLengthFieldOffset(0)
     *       .setLengthFieldLength(2)
     *       .handler(new TcpMethod() {
     *           @Override
     *           public void handle(InputStream in, OutputStream out) throws Exception {
     *               byte[] frame;
     *               while ((frame = receiveFrame(in)) != null) {
     *                   // frame 包含头部 + 数据
     *                   out.write(frame);
     *                   out.flush();
     *               }
     *           }
     *       });
     * }</pre>
     */
    public interface TcpMethod extends TcpHandler {
        /**
         * 接收固定长度的数据帧。
         *
         * @param in   输入流
         * @param size 期望的帧大小
         * @return 完整的帧数据
         * @throws IOException IO 异常
         */
        default byte[] receiveFrame(InputStream in, int size) throws IOException {
            return JdkTcpServer.receiveFrame(in, size);
        }

        /**
         * 接收带长度字段的帧。
         *
         * @param in 输入流
         * @return 完整的帧数据
         * @throws IOException IO 异常
         */
        default byte[] receiveFrame(InputStream in) throws IOException {
            return JdkTcpServer.receiveFrame(in, 0, 4, 0, 1);
        }
    }
}
