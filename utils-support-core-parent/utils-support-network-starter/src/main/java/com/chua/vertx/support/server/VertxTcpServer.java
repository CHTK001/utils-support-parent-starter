package com.chua.vertx.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkTcpServer;
import com.chua.common.support.spi.annotations.Spi;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
public class VertxTcpServer extends AbstractServer {

    private Vertx vertx;
    private NetServer netServer;
    private ExecutorService workerPool;
    private final Map<String, JdkTcpServer.TcpHandler> handlers = new ConcurrentHashMap<>();

    public VertxTcpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        try {
            VertxOptions opts = new VertxOptions()
                    .setEventLoopPoolSize(Math.max(Runtime.getRuntime().availableProcessors(), 2))
                    .setWorkerPoolSize(Math.max(setting.getWorkerThreads(),
                            Runtime.getRuntime().availableProcessors() * 4))
                    .setPreferNativeTransport(true);
            vertx = Vertx.vertx(opts);
            workerPool = Executors.newVirtualThreadPerTaskExecutor();

            NetServerOptions options = new NetServerOptions()
                    .setHost(setting.getHost())
                    .setPort(setting.getPort())
                    .setTcpNoDelay(setting.isTcpNoDelay())
                    // backlog 下限 65536:万级并发连接突发下避免内核 accept 队列溢出
                    .setAcceptBacklog(Math.max(setting.getBacklog(), 65536))
                    .setReuseAddress(setting.isSoReuseAddr())
                    // 收发缓冲放大:与内核窗口对齐,减少小包分片与 ACK 往返,提升高并发吞吐
                    .setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384))
                    .setSendBufferSize(Math.max(setting.getBufferSize(), 16384));
            netServer = vertx.createNetServer(options);
            netServer.connectHandler(this::handleSocket);
            // Vert.x 5.x:listen 返回 Future,异步完成;用 latch 等监听就绪并回填端口,
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
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

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
        } else {
            // 默认回显:事件循环直接写回(非阻塞,高吞吐);
            // 写队列水位放宽到 1MB,避免大报文突发写回时触发背压丢吞吐
            socket.setWriteQueueMaxSize(1024 * 1024);
            socket.handler(socket::write);
        }
    }

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

    /** 基于 NetSocket 的 InputStream(阻塞读,虚拟线程专用)。 */
    private static final class NetSocketInputStream extends InputStream {
        private final NetSocket socket;
        private final java.util.concurrent.LinkedBlockingQueue<Byte> queue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private boolean closed;

        NetSocketInputStream(NetSocket socket) {
            this.socket = socket;
            socket.handler(buf -> {
                for (byte b : buf.getBytes()) {
                    queue.offer(b);
                }
            });
            socket.closeHandler(v -> closed = true);
        }

        @Override
        public int read() throws IOException {
            while (!closed || !queue.isEmpty()) {
                Byte b = queue.poll();
                if (b != null) {
                    return b & 0xFF;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (b.length == 0) {
                return 0;
            }
            int c = read();
            if (c < 0) {
                return -1;
            }
            b[off] = (byte) c;
            return 1;
        }
    }

    /** 基于 NetSocket 的 OutputStream(阻塞写,虚拟线程专用)。 */
    private static final class NetSocketOutputStream extends OutputStream {
        private final NetSocket socket;

        NetSocketOutputStream(NetSocket socket) {
            this.socket = socket;
        }

        @Override
        public void write(int b) {
            socket.write(io.vertx.core.buffer.Buffer.buffer(new byte[]{(byte) b}));
        }

        @Override
        public void write(byte[] b, int off, int len) {
            socket.write(io.vertx.core.buffer.Buffer.buffer(java.util.Arrays.copyOfRange(b, off, off + len)));
        }
    }
}
