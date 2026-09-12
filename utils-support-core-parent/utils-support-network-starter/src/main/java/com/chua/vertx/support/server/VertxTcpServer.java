package com.chua.vertx.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkTcpServer;
import com.chua.common.support.network.server.impl.TcpServerRequest;
import com.chua.common.support.network.server.impl.TcpServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 基于 Vert.x {@link NetServer} 的 TCP 服务器实现,与 {@link JdkTcpServer} 能力对齐:
 * <ul>
 *   <li><b>回显</b>(默认):NetSocket 收到数据后原样写回(事件循环驱动,非阻塞)</li>
 *   <li><b>Handler</b>:通过 {@link #registerHandler(String, TcpHandler)} 注册,
 *       兼容 {@link JdkTcpServer.TcpHandler} 流式接口(虚拟线程执行)</li>
 *   <li><b>keep-alive</b>:连接不主动关闭,可持续读写</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/16
 */
@Slf4j
@Spi("vertx-tcp")
public class VertxTcpServer extends AbstractServer implements com.chua.common.support.network.tcp.TcpServer {

    /** Vertx */
    private Vertx vertx;
    /** NET服务器 */
    private NetServer netServer;
    /** 工人池 */
    private ExecutorService workerPool;
    /** 处理器 */
    private final Map<String, JdkTcpServer.TcpHandler> handlers = new ConcurrentHashMap<>();
    /** 帧处理器（tcp服务端 接口，短连接一请求一响应） */
    private com.chua.common.support.network.tcp.callback.TcpServerHandler frameHandler;

    /**
      * 创建 vertxtcp服务端 实例
     * @param setting setting
     */
    public VertxTcpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** 注册帧处理器（tcp服务端 接口） */
    public VertxTcpServer setHandler(com.chua.common.support.network.tcp.callback.TcpServerHandler handler) {
        this.frameHandler = handler;
        return this;
    }

    @Override
    /** 执行开始 */
    protected void doStart() {
        try {
            VertxOptions opts = new VertxOptions()
                    .setEventLoopPoolSize(Math.max(Runtime.getRuntime().availableProcessors(), 2))
                    .setWorkerPoolSize(Math.max(setting.getWorkerThreads(),
                            Runtime.getRuntime().availableProcessors() * 4))
                    .setPreferNativeTransport(true);
            vertx = Vertx.vertx(opts);
            workerPool = ThreadUtils.newVirtualThreadPerTaskExecutor();

            NetServerOptions options = new NetServerOptions()
                    .setHost(setting.getHost())
                    .setPort(setting.getPort())
                    .setTcpNoDelay(setting.isTcpNoDelay())
                    // backlog 下限 65536:万级并发连接突发下避免内核 accept 队列溢出
                    .setAcceptBacklog(Math.max(setting.getBacklog(), 65536))
                    .setReuseAddress(setting.isSoReuseAddr())
                    // 收发缓冲放大:与内核窗口对齐,减少小包分片与 ACK 往返,提升高并发吞吐
                    .setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384))
                    .setSendBufferSize(Math.max(setting.getBufferSize(), 16384))
 // 吞吐优化:TCP_QUICKACK 减少 ACK 延迟,fast打开 加速握手
 // 注意:不使用 TCP_CORK——它延迟发送最多 200ms 合并小包,对 HTTP 合并 头部/主体 有利,
                    // 但对小报文 echo/流式协议每个响应都要等 200ms 才发出,吞吐暴跌(实测 QPS 从 4.9 万掉到 1.2 千)
                    .setTcpQuickAck(true)
                    .setTcpFastOpen(true)
                    .setTcpKeepAlive(true);
            netServer = vertx.createNetServer(options);
            netServer.connectHandler(this::handleSocket);
 // Vert.x 5.x:监听 返回 期货,异步完成;用 插销 等监听就绪并回填端口,
            // 否则 start() 返回时端口仍为 0,客户端无法连接
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
            netServer.listen().onSuccess(server -> {
                setting.setPort(netServer.actualPort());
                log.info("Vertx TcpServer started on {}:{} (eventLoops={}, reactive=true)",
                        setting.getHost(), setting.getPort(),
                        Runtime.getRuntime().availableProcessors());
                ready.countDown();
            }).onFailure(err -> {
                log.error("Vertx TcpServer 启动失败: {}", err.getMessage(), err);
                ready.countDown();
            });
            if (!ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("Vertx TcpServer 监听启动超时");
            }
        } catch (Exception e) {
            throw new RuntimeException("Vertx TcpServer 启动失败", e);
        }
    }

    @Override
    /** 执行停止 */
    protected void doStop() {
        if (netServer != null) {
            try {
                netServer.close();
            } catch (Exception ignored) {
            }
            log.info("Vertx TcpServer stopped");
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
        if (vertx != null) {
            try {
                vertx.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    /** 获取协议类型 */
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    /**
     * 处理套接字
     *
     * @param socket 套接字
     */
    private void handleSocket(NetSocket socket) {
        String clientKey = socket.remoteAddress() != null ? socket.remoteAddress().toString() : "";
        JdkTcpServer.TcpHandler handler = findHandler(clientKey);
        if (handler != null) {
            // Handler 模式:虚拟线程执行流式接口(与 JdkTcpServer 对齐)
            workerPool.submit(() -> {
                try {
                    InputStream in = new NetSocketInputStream(socket);
                    OutputStream out = new NetSocketOutputStream(socket);
                    handler.handle(in, out);
                } catch (Exception e) {
                    log.debug("TCP 连接处理异常: {}", e.getMessage());
                } finally {
                    socket.close();
                }
            });
        } else if (urlMappingFilter != null && urlMappingFilter.getFactory().routeCount() > 0) {
 // URL 路由模式：走 过滤器 Chain
            workerPool.submit(() -> processViaFilterChain(socket));
        } else if (frameHandler != null) {
            // 帧模式(TcpServer 接口):短连接一请求一响应,读完整帧→处理→写响应→关闭
            workerPool.submit(() -> {
                try {
                    InputStream in = new NetSocketInputStream(socket);
                    OutputStream out = new NetSocketOutputStream(socket);
                    byte[] frame = readFrame(in);
                    if (frame != null) {
                        byte[] response = frameHandler.handle(frame);
                        if (response != null) {
 // 响应帧协议（与 jdktcp客户端.exchange 对称）：4 字节长度头 + 主体
                            byte[] len = new byte[4];
                            len[0] = (byte) (response.length >>> 24);
                            len[1] = (byte) (response.length >>> 16);
                            len[2] = (byte) (response.length >>> 8);
                            len[3] = (byte) response.length;
                            out.write(len);
                            out.write(response);
                            out.flush();
                        }
                    }
                } catch (Exception e) {
                    log.debug("帧处理异常: {}", e.getMessage());
                } finally {
                    socket.close();
                }
            });
        } else {
            // 默认回显:事件循环直接写回(非阻塞,高吞吐);
            // 写队列水位放宽到 1MB,避免大报文突发写回时触发背压丢吞吐
            socket.setWriteQueueMaxSize(1024 * 1024);
            socket.handler(socket::write);
        }
    }

    /**
      * 通过 过滤器 Chain 处理请求（URL 路由模式），将完整 HTTP 响应写回 net套接字。
     * @param socket 套接字
     */
    private void processViaFilterChain(NetSocket socket) {
        try {
            byte[] frame = readFrame(new NetSocketInputStream(socket));
            if (frame == null) {
                return;
            }
            TcpServerRequest request = new TcpServerRequest(frame, null, StandardCharsets.UTF_8);
            TcpServerResponse response = new TcpServerResponse();
            try {
                handleRequest(request, response);
            } catch (Exception e) {
                log.debug("帧处理器异常: {}", e.getMessage());
                return;
            }
            if (!response.isEnded()) {
                response.end();
            }
            byte[] respFrame = response.getReadyBytes();
            if (respFrame != null && respFrame.length > 0) {
                byte[] len = new byte[4];
                len[0] = (byte) (respFrame.length >>> 24);
                len[1] = (byte) (respFrame.length >>> 16);
                len[2] = (byte) (respFrame.length >>> 8);
                len[3] = (byte) respFrame.length;
                socket.write(Buffer.buffer(len));
                socket.write(Buffer.buffer(respFrame));
            }
        } catch (Exception e) {
            log.debug("URL 路由处理异常: {}", e.getMessage());
        } finally {
            socket.close();
        }
    }

    /**
      * 读取一帧（与 jdktcp客户端 长度帧协议对称：4 字节长度头 + scatter帧 主体）。
     *
     * @param in 输入流
     * @return 完整帧字节，EOF 返回 空
     * @param buf buf
     /**
      * 读取帧。
      * @param in 入
      * @return 读取帧的结果
      */
      * @param buf buf
     /**
      * 读取帧。
      * @param in 入
      * @return 读取帧的结果
      */
      * @param buf buf
     /**
      * 读取帧。
      * @param in 入
      * @return 读取帧的结果
      */
     */
    private static byte[] readFrame(InputStream in) throws IOException {
        byte[] lenBytes = new byte[4];
        int n = readFully(in, lenBytes);
        if (n == -1) {
            return null;
        }
        if (n < 4) {
            throw new IOException("帧长度头不完整: " + n);
        }
        int bodyLen = ((lenBytes[0] & 0xff) << 24) | ((lenBytes[1] & 0xff) << 16)
                | ((lenBytes[2] & 0xff) << 8) | (lenBytes[3] & 0xff);
        if (bodyLen <= 0 || bodyLen > 16 * 1024 * 1024) {
            throw new IOException("帧长度越界: " + bodyLen);
        }
        byte[] body = new byte[bodyLen];
        readFully(in, body);
        return body;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r == -1) {
                return total == 0 ? -1 : total;
            }
            total += r;
        }
        return total;
    }

    /**
     * 查找处理器
     *
     * @param clientKey 客户端键
     * @return find处理器的结果
     */
    private JdkTcpServer.TcpHandler findHandler(String clientKey) {
        JdkTcpServer.TcpHandler handler = handlers.get(clientKey);
        if (handler != null) {
            return handler;
        }
        for (Map.Entry<String, JdkTcpServer.TcpHandler> entry : handlers.entrySet()) {
            String key = entry.getKey();
            if ("*".equals(key) || clientKey.contains(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 注册 TCP 处理器(兼容 {@link JdkTcpServer.TcpHandler})。
     *
     * @param name   连接标识(支持 "*" 通配与地址前缀匹配)
     * @param handler 处理器
     * @return 当前实例
     */
    public VertxTcpServer registerHandler(String name, JdkTcpServer.TcpHandler handler) {
        handlers.put(name, handler);
        return this;
    }

    /** 基于 net套接字 的 输入流(阻塞读,虚拟线程专用)。 */
    private static final class NetSocketInputStream extends InputStream {
        /** 数据段:一次性拷贝 Vert.x 缓冲 的 backing bytes,避免 per-byte boxing */
        private static final class Segment {
            final byte[] data;
            int pos;
            Segment(byte[] data) { this.data = data; }
        }
        /** 结束哨兵(关闭信号) */
        private static final Segment EOS = new Segment(new byte[0]);
        /** 套接字 */
        private final NetSocket socket;
        /** 数据段队列:链接阻塞队列.取() 自带 锁支持.park 阻塞(替代 Thread.sleep 轮询) */
        private final LinkedBlockingQueue<Segment> queue = new LinkedBlockingQueue<>();
        /** Closed */
        private volatile boolean closed;
        /** 当前正在读的数据段 */
        private Segment current;

        NetSocketInputStream(NetSocket socket) {
            this.socket = socket;
            socket.handler(buf -> {
                if (buf.length() == 0) {
                    return;
                }
 // 拷贝一次就好,避免 Vert.x 缓冲 被底层回收而我们在排队
                queue.offer(new Segment(buf.getBytes()));
            });
            socket.closeHandler(v -> {
                closed = true;
                queue.offer(EOS);
            });
        }

        @Override
        /** 读取 */
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) == -1 ? -1 : b[0] & 0xFF;
        }

        @Override
        /** 读取 */
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            // 确保手头有含未读字节的数据段
            while (current == null || current.pos >= current.data.length) {
                if (closed && queue.isEmpty()) {
                    return -1;
                }
                try {
                    Segment seg = queue.take();
                    if (seg == EOS) {
                        return -1;
                    }
                    current = seg;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            int n = Math.min(current.data.length - current.pos, len);
            System.arraycopy(current.data, current.pos, b, off, n);
            current.pos += n;
            return n;
        }

        @Override
        /** 可用 */
        public int available() {
            Segment c = current;
            int avail = (c == null || c.pos >= c.data.length) ? 0 : (c.data.length - c.pos);
            avail += queue.stream().mapToInt(s -> s == EOS ? 0 : s.data.length - s.pos).sum();
            return avail;
        }
    }

    /** 基于 net套接字 的 输出流(阻塞写,虚拟线程专用)。 */
    private static final class NetSocketOutputStream extends OutputStream {
        /** 套接字 */
        private final NetSocket socket;
        /** 攒批缓冲：写入(int)/小块写入先入缓冲，flush 时一次性写 套接字（避免逐字节 Vert.x 调用） */
        private final byte[] buf = new byte[8192];
        private int pos;

        NetSocketOutputStream(NetSocket socket) {
            this.socket = socket;
        }

        @Override
        /** 写入 */
        public void write(int b) {
            if (pos >= buf.length) {
                flush();
            }
            buf[pos++] = (byte) b;
        }

        @Override
        /** 写入 */
        public void write(byte[] b, int off, int len) {
            if (len >= buf.length) {
                // 大块直接写，避免缓冲中转（Vert.x 无 buffer(byte[],off,len) 重载，需拷贝）
                flush();
                socket.write(io.vertx.core.buffer.Buffer.buffer(
                        java.util.Arrays.copyOfRange(b, off, off + len)));
                return;
            }
            if (pos + len > buf.length) {
                flush();
            }
            System.arraycopy(b, off, buf, pos, len);
            pos += len;
        }

        @Override
        /** 刷写 */
        public void flush() {
            if (pos > 0) {
 // 拷贝后写：避免 缓冲 共享内部攒批数组（后续覆写会影响异步发送）
                socket.write(io.vertx.core.buffer.Buffer.buffer(java.util.Arrays.copyOf(buf, pos)));
                pos = 0;
            }
        }
    }
}
