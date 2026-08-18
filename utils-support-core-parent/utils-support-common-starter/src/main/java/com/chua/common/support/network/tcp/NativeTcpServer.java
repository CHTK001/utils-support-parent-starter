package com.chua.common.support.network.tcp;

import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 JDK NIO 多 Selector 反应器的 TCP 长度帧服务端实现。
 *
 * <p>采用「一个接收线程 + 多个 IO Selector 线程」的 Reactor 模式：</p>
 * <ul>
 *   <li>接收线程只处理 {@code OP_ACCEPT}，接受连接后轮询注册到某个 IO Selector，分散读压力</li>
 *   <li>IO 线程只分发读事件与拼帧，业务处理交由 worker 线程池，避免业务慢操作阻塞 IO 事件循环</li>
 * </ul>
 *
 * <p>帧协议：4 字节大端长度头 + 消息体。消息体长度受 {@link #MAX_BODY_SIZE} 上限约束，
 * 防止恶意客户端构造超长报文导致 OOM。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("native-tcp")
public class NativeTcpServer implements TcpServer {

    /**
     * 长度头字节数
     */
    private static final int HEADER_SIZE = 4;

    /**
     * 消息体长度上限（字节），默认 8MB
     */
    private static final int MAX_BODY_SIZE = 8 * 1024 * 1024;

    /**
     * 默认工作线程数
     */
    private static final int DEFAULT_WORKERS = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * 监听主机
     */
    private final String host;

    /**
     * 监听端口（0 表示系统分配）
     */
    private final int port;

    /**
     * IO Selector 线程数
     */
    private final int ioThreadsCount;

    /**
     * 工作线程数
     */
    private final int workerThreads;

    /**
     * 帧处理器
     */
    private volatile TcpServerHandler handler;

    /**
     * 工作线程池
     */
    private final ExecutorService workerPool;

    /**
     * 服务器通道
     */
    private ServerSocketChannel serverChannel;

    /**
     * 接收连接用 Selector（专用线程）
     */
    private Selector acceptSelector;

    /**
     * IO Selector 数组（多线程分发读事件）
     */
    private Selector[] ioSelectors;

    /**
     * IO Selector 线程数组
     */
    private Thread[] ioThreads;

    /**
     * 下一个 IO Selector 分配游标（轮询注册新连接）
     */
    private final AtomicInteger ioCursor = new AtomicInteger();

    /**
     * 运行标志
     */
    private volatile boolean running;

    /**
     * 接收连接专用线程
     */
    private Thread acceptThread;

    /**
     * 实际监听端口（端口为 0 时系统分配后回填）
     */
    private volatile int boundPort;

    /**
     * 创建 TCP 长度帧服务端。
     *
     * @param host       监听主机
     * @param port       监听端口
     * @param ioThreads  IO Selector 线程数，小于等于 0 时使用默认 CPU 核数
     * @param workers    工作线程数，小于等于 0 时使用默认值
     */
    public NativeTcpServer(String host, int port, int ioThreads, int workers) {
        this.host = host == null ? "0.0.0.0" : host;
        this.port = port;
        this.ioThreadsCount = ioThreads > 0 ? ioThreads : Runtime.getRuntime().availableProcessors();
        this.workerThreads = workers > 0 ? workers : DEFAULT_WORKERS;
        this.workerPool = ThreadUtils.newFixedThreadExecutor(workerThreads, "native-tcp-worker");
    }

    @Override
    public TcpServer setHandler(TcpServerHandler handler) {
        this.handler = handler;
        return this;
    }

    @Override
    public TcpServer start() {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(host, port));
            this.boundPort = serverChannel.socket().getLocalPort();
            acceptSelector = Selector.open();
            serverChannel.register(acceptSelector, SelectionKey.OP_ACCEPT);
            running = true;
            ioSelectors = new Selector[ioThreadsCount];
            ioThreads = new Thread[ioThreadsCount];
            for (int i = 0; i < ioThreadsCount; i++) {
                final Selector ioSelector = Selector.open();
                ioSelectors[i] = ioSelector;
                final int idx = i;
                ioThreads[i] = new Thread(() -> ioEventLoop(ioSelector), "native-tcp-io-" + idx);
                ioThreads[i].setDaemon(true);
                ioThreads[i].start();
            }
            acceptThread = new Thread(this::acceptLoop, "native-tcp-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("NativeTcpServer started on {}:{} (ioThreads={}, workers={})",
                    host, boundPort, ioThreadsCount, workerThreads);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start NativeTcpServer", e);
        }
        return this;
    }

    @Override
    public int getPort() {
        return boundPort;
    }

    /**
     * 接收连接专用线程：accept 后轮询注册到某个 IO Selector。
     */
    private void acceptLoop() {
        while (running) {
            try {
                acceptSelector.select(1000);
                Set<SelectionKey> keys = acceptSelector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        doAccept(key);
                    }
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (IOException e) {
                log.error("Accept selector error", e);
            }
        }
    }

    /**
     * IO Selector 线程：只负责读事件分发与拼帧，业务处理交给 worker 线程池。
     *
     * @param ioSelector 该线程专属的 Selector
     */
    private void ioEventLoop(Selector ioSelector) {
        while (running) {
            try {
                ioSelector.select(1000);
                Set<SelectionKey> keys = ioSelector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isReadable()) {
                        doRead(key);
                    }
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (IOException e) {
                log.error("IO selector error", e);
            }
        }
    }

    private void doAccept(SelectionKey key) throws IOException {
        SocketChannel sc = ((ServerSocketChannel) key.channel()).accept();
        if (sc != null) {
            sc.configureBlocking(false);
            // 轮询选择一个 IO Selector 注册，分散读事件压力
            Selector ioSelector = ioSelectors[Math.floorMod(ioCursor.getAndIncrement(), ioSelectors.length)];
            sc.register(ioSelector, SelectionKey.OP_READ, new Attachment(ioSelector));
        }
    }

    private void doRead(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        Attachment att = (Attachment) key.attachment();
        if (readFrame(sc, att)) {
            byte[] body = att.bodyBuf.array();
            att.reset();
            workerPool.execute(() -> processRequest(sc, body, att));
        }
    }

    private boolean readFrame(SocketChannel sc, Attachment att) throws IOException {
        if (att.state == State.HEADER) {
            // 只在起始位置清空 header，避免半包场景下把已读字节清掉导致数据丢失
            if (att.headerBuf.position() == 0) {
                att.headerBuf.clear();
            }
            int r = sc.read(att.headerBuf);
            if (r == -1) {
                closeChannel(keyFor(sc, att));
                return false;
            }
            if (att.headerBuf.position() < HEADER_SIZE) {
                return false;
            }
            att.headerBuf.flip();
            att.bodyLen = att.headerBuf.getInt();
            if (att.bodyLen <= 0 || att.bodyLen > MAX_BODY_SIZE) {
                closeChannel(keyFor(sc, att));
                throw new IOException("Invalid body length: " + att.bodyLen);
            }
            att.bodyBuf = ByteBuffer.allocate(att.bodyLen);
            att.state = State.BODY;
        }
        int r = sc.read(att.bodyBuf);
        if (r == -1) {
            closeChannel(keyFor(sc, att));
            return false;
        }
        return !att.bodyBuf.hasRemaining();
    }

    private SelectionKey keyFor(SocketChannel sc, Attachment att) {
        return sc.keyFor(att.ioSelector);
    }

    private void processRequest(SocketChannel sc, byte[] reqData, Attachment att) {
        try {
            byte[] respData = handler.handle(reqData);
            if (respData != null) {
                writeResponse(sc, respData);
            }
        } catch (Exception e) {
            log.error("Process request error", e);
            closeChannel(keyFor(sc, att));
        }
    }

    /**
     * 将响应帧完整写入通道。通道为非阻塞模式，必须循环写入直至缓冲区耗尽，避免粘包/半包。
     *
     * @param sc       目标通道
     * @param respData 响应帧字节（不含长度头）
     */
    private void writeResponse(SocketChannel sc, byte[] respData) {
        try {
            if (respData.length > MAX_BODY_SIZE) {
                log.warn("Response too large: {}", respData.length);
                return;
            }
            ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + respData.length);
            buf.putInt(respData.length);
            buf.put(respData);
            buf.flip();
            while (buf.hasRemaining()) {
                int written = sc.write(buf);
                if (written <= 0) {
                    // 非阻塞模式下无更多空间，短暂让步避免忙等
                    Thread.yield();
                }
            }
        } catch (Exception e) {
            log.error("Write response error", e);
        }
    }

    private void closeChannel(SelectionKey key) {
        if (key != null) {
            try {
                key.channel().close();
            } catch (IOException ignored) {
            }
            key.cancel();
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            if (acceptSelector != null) {
                acceptSelector.wakeup();
                acceptSelector.close();
            }
        } catch (IOException ignored) {
        }
        if (ioSelectors != null) {
            for (Selector ioSelector : ioSelectors) {
                try {
                    if (ioSelector != null) {
                        ioSelector.wakeup();
                        ioSelector.close();
                    }
                } catch (IOException ignored) {
                }
            }
        }
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }
        ThreadUtils.closeQuietly(workerPool);
        log.info("NativeTcpServer closed");
    }

    private enum State { HEADER, BODY }

    /**
     * 连接级粘包拼帧上下文。
     */
    private static class Attachment {

        /**
         * 长度头缓冲区
         */
        final ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);

        /**
         * 归属的 IO Selector（用于 keyFor 关闭连接时定位注册表）
         */
        final Selector ioSelector;

        /**
         * 当前拼帧阶段
         */
        State state = State.HEADER;

        /**
         * 消息体长度
         */
        int bodyLen;

        /**
         * 消息体缓冲区
         */
        ByteBuffer bodyBuf;

        /**
         * 创建连接级拼帧上下文。
         *
         * @param ioSelector 归属的 IO Selector
         */
        Attachment(Selector ioSelector) {
            this.ioSelector = ioSelector;
        }

        /**
         * 重置拼帧上下文，等待下一帧请求。
         */
        void reset() {
            headerBuf.clear();
            state = State.HEADER;
            bodyBuf = null;
        }
    }
}