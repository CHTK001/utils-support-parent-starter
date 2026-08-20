package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 NIO + Reactor + 虚拟线程的 TCP 同步服务端实现。
 *
 * <p>对比旧版 BIO（每连接一个阻塞线程）的改进：</p>
 * <ul>
 *     <li><b>NIO Selector 事件循环</b>：单个 Reactor 线程管理所有连接的 accept/read/write 事件，连接数不再受线程数限制；</li>
 *     <li><b>虚拟线程处理业务</b>：Reactor 线程只做 IO 就绪检测与行切分，业务回调（onMessage 等）交由虚拟线程执行，避免业务阻塞事件循环；</li>
 *     <li><b>非阻塞写</b>：写入按连接加锁 + 发送缓冲，广播/定向发送均不阻塞 Reactor 线程。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("tcp")
public class TcpSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 读取缓冲大小
     */
    private static final int READ_BUFFER_SIZE = 16384;

    /**
     * IO 缓冲线程本地实例（worker 复用一个，避免每事件 new 导致 GC 压力）
     */
    private static final ThreadLocal<ByteBuffer> IO_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(READ_BUFFER_SIZE));

    /**
     * 客户端连接表（clientId -> connection）
     */
    private final Map<String, ClientConnection> clients = new ConcurrentHashMap<>();

    /**
     * 同步监听器
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 服务端通道
     */
    private ServerSocketChannel serverChannel;

    /**
     * Boss Selector（仅负责 accept）
     */
    private Selector bossSelector;

    /**
     * Boss 事件循环线程
     */
    private Thread bossThread;

    /**
     * Worker Selector 数组（每个 worker 一个事件循环，负责 read/write）
     */
    private Selector[] workerSelectors;

    /**
     * Worker 事件循环线程数组
     */
    private Thread[] workerThreads;

    /**
     * Worker 待注册连接队列(accept 后按轮询分发)
     */
    private final List<java.util.concurrent.ConcurrentLinkedQueue<SocketChannel>> pendingRegistrations =
            new CopyOnWriteArrayList<>();

    /**
     * Worker 数量
     */
    private int workerCount = 1;

    /**
     * Worker 轮询索引
     */
    private final java.util.concurrent.atomic.AtomicInteger workerIndex = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 运行状态
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 空闲构造。
     */
    public TcpSyncServer() {
        super(null);
    }

    /**
     * 配置构造。
     *
     * @param setting 服务器配置
     */
    public TcpSyncServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** 获取Protocol */
    public String getProtocol() {
        return "tcp";
    }

    @Override
    /** 创建Server */
    public SyncServer createServer(ServerSetting setting) {
        return new TcpSyncServer(setting);
    }

    @Override
    /** 创建Client */
    public SyncClient createClient(Object setting) {
        String url = setting instanceof String ? (String) setting : "tcp://127.0.0.1:19391";
        return new TcpSyncClient(url);
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), setting.getBacklog());
            // 主从多线程 Reactor: boss 仅 accept, workCount 个 worker 各自 Selector 处理 read/write,
            // 避免单 Selector 单线程在千级并发下成为 IO 事件瓶颈
            workerCount = Math.max(1, Math.min(
                Integer.getInteger("chua.tcp.reactor.workers",
                        Math.min(Runtime.getRuntime().availableProcessors(), 4)), 8));
            bossSelector = Selector.open();
            serverChannel.register(bossSelector, SelectionKey.OP_ACCEPT);
            workerSelectors = new Selector[workerCount];
            workerThreads = new Thread[workerCount];
            pendingRegistrations.clear();
            running.set(true);
            for (int i = 0; i < workerCount; i++) {
                Selector worker = Selector.open();
                workerSelectors[i] = worker;
                pendingRegistrations.add(new java.util.concurrent.ConcurrentLinkedQueue<>());
                final int idx = i;
                Thread wt = ThreadUtils.newThread(() -> workerLoop(idx, worker), "tcp-sync-worker-" + setting.getPort() + "-" + i);
                wt.setDaemon(true);
                wt.start();
                workerThreads[i] = wt;
            }
            bossThread = ThreadUtils.newThread(this::bossLoop, "tcp-sync-boss-" + setting.getPort());
            bossThread.setDaemon(true);
            bossThread.start();
        } catch (IOException e) {
            throw new RuntimeException("TCP SyncServer 启动失败", e);
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        running.set(false);
        if (bossSelector != null) {
            try {
                bossSelector.wakeup();
            } catch (Exception ignored) {
            }
        }
        if (workerSelectors != null) {
            for (Selector worker : workerSelectors) {
                if (worker != null) {
                    try {
                        worker.wakeup();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (bossThread != null) {
            try {
                bossThread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            bossThread = null;
        }
        if (workerThreads != null) {
            for (Thread wt : workerThreads) {
                if (wt != null) {
                    try {
                        wt.join(2000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            workerThreads = null;
        }
        // 关闭 Selector 释放通道与端口资源:否则 stop 后同端口重启 bind 会
        // 残留旧 Selector 状态,新实例 start() 可能阻塞/复用失败(故障测试实测)
        if (bossSelector != null) {
            try {
                bossSelector.close();
            } catch (Exception ignored) {
            }
            bossSelector = null;
        }
        if (workerSelectors != null) {
            for (Selector worker : workerSelectors) {
                if (worker != null) {
                    try {
                        worker.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            workerSelectors = null;
        }
        pendingRegistrations.clear();
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
            serverChannel = null;
        }
        for (ClientConnection connection : clients.values()) {
            connection.close();
        }
        clients.clear();
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message;
        for (ClientConnection connection : clients.values()) {
            connection.write(payload);
        }
    }

    @Override
    /** 发送 */
    public void send(String clientId, String topic, Object message) {
        ClientConnection connection = clients.get(clientId);
        if (connection == null) {
            return;
        }
        connection.write(topic + ":" + message);
    }

    @Override
    /** 获取ConnectedClients */
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    /** 获取ClientMetadata */
    public Map<String, Object> getClientMetadata(String clientId) {
        ClientConnection connection = clients.get(clientId);
        return connection != null ? Collections.unmodifiableMap(connection.metadata) : Collections.emptyMap();
    }

    @Override
    /** 添加Listener */
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 移除Listener */
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    /**
     * Boss 事件循环: 仅处理 accept,新连接轮询分发给 worker(主从 Reactor)。
     */
    private void bossLoop() {
        while (running.get() && bossSelector.isOpen()) {
            try {
                bossSelector.select(500);
                var selected = bossSelector.selectedKeys();
                var iterator = selected.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        }
                    } catch (IOException e) {
                        try {
                            key.channel().close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("TCP SyncServer Boss 循环异常", e);
                }
            }
        }
    }

    /**
     * Worker 事件循环: 处理各自注册连接的 read/write。
     *
     * @param idx worker 序号
     * @param worker 对应 Selector
     */
    private void workerLoop(int idx, Selector worker) {
        var pending = pendingRegistrations.get(idx);
        while (running.get() && worker.isOpen()) {
            try {
                // 先注册 boss 分发的待注册连接(写事件暂时无人设置,只需注册读)
                SocketChannel newChannel;
                while ((newChannel = pending.poll()) != null) {
                    if (newChannel.isOpen()) {
                        ClientConnection connection = new ClientConnection(newChannel, worker);
                        newChannel.register(worker, SelectionKey.OP_READ, connection);
                    }
                }
                worker.select(500);
                var selected = worker.selectedKeys();
                var iterator = selected.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    try {
                        if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (IOException e) {
                        handleChannelError(key, e);
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("TCP SyncServer Worker{} 循环异常", idx, e);
                }
            }
        }
    }

    /**
     * 处理 accept 事件: 从 boss accept 后轮询分发到 worker 待注册队列。
     *
     * @param key 选择键
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel channel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel;
        while ((socketChannel = channel.accept()) != null) {
            socketChannel.configureBlocking(false);
            socketChannel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
            int target = workerIndex.getAndIncrement() & 0x7FFFFFFF;
            pendingRegistrations.get(target % workerCount).offer(socketChannel);
            Selector worker = workerSelectors[target % workerCount];
            if (worker != null) {
                worker.wakeup();
            }
        }
    }

    /**
     * 处理 read 事件：非阻塞读入缓冲，按行切分后交虚拟线程处理。
     *
     * @param key 选择键
     */
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientConnection connection = (ClientConnection) key.attachment();
        ByteBuffer buffer = IO_BUFFER.get();
        buffer.clear();
        int read;
        while ((read = channel.read(buffer)) > 0) {
            buffer.flip();
            connection.appendBuffer(buffer);
            buffer.clear();
        }
        if (read == -1) {
            // 对端关闭
            connection.unregister();
            return;
        }
        // 切分完整行并交虚拟线程处理
        List<String> lines = connection.drainLines();
        for (String line : lines) {
            final String msg = line;
            Thread.ofVirtual().name("tcp-sync-handler").start(() -> connection.handleLine(msg));
        }
    }

    /**
     * 处理 write 事件：冲刷连接发送队列。
     *
     * @param key 选择键
     */
    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientConnection connection = (ClientConnection) key.attachment();
        connection.flushQueue(channel, key);
    }

    /**
     * 通道异常：通知监听器并关闭连接。
     *
     * @param key 选择键
     * @param e   异常
     */
    private void handleChannelError(SelectionKey key, IOException e) {
        ClientConnection connection = (ClientConnection) key.attachment();
        if (connection != null) {
            notifyListener(l -> l.onError(connection.getClientId(), e));
            connection.unregister();
        } else {
            key.cancel();
        }
    }

    /**
     * 通知监听器。
     *
     * @param action 动作
     */
    private void notifyListener(java.util.function.Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 客户端连接封装（NIO 非阻塞）。
     *
     */
    private final class ClientConnection {

        /**
         * 底层通道
         */
        private final SocketChannel channel;

        /**
         * 所属 Worker Selector(连接固定由该 worker 处理 read/write)
         */
        private final Selector worker;

        /**
         * 待续写的数据(非阻塞 write 一次写不完时保留,随 OP_WRITE 再续)
         */
        private ByteBuffer pendingWrite;

        /**
         * 客户端标识
         */
        private volatile String clientId;

        /**
         * 客户端元数据
         */
        private final Map<String, Object> metadata = new HashMap<>();

        /**
         * 读取缓冲（按行切分前的原始字节）
         */
        private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE * 2);

        /**
         * 发送队列（写事件就绪时冲刷）
         */
        private final LinkedBlockingQueue<String> writeQueue = new LinkedBlockingQueue<>();

        /**
         * 是否已注册写事件
         */
        private final AtomicBoolean writePending = new AtomicBoolean(false);

        /**
         * 创建客户端连接。
         *
         * @param channel 底层通道
         * @param worker  所属 Worker Selector
         */
        private ClientConnection(SocketChannel channel, Selector worker) {
            this.channel = channel;
            this.worker = worker;
            // 初始置为读模式（无数据）：position=0, limit=0，供 appendBuffer 的 compact() 正确腾出空间
            readBuffer.flip();
        }

        /**
         * 追加读取字节。调用前 readBuffer 处于读模式（position=0, limit=数据末尾）。
         *
         * @param buffer 数据
         */
        void appendBuffer(ByteBuffer buffer) {
            synchronized (readBuffer) {
                readBuffer.compact();
                while (buffer.hasRemaining()) {
                    readBuffer.put(buffer.get());
                }
                readBuffer.flip();
            }
        }

        /**
         * 切分完整行（按 \n），未完成行保留在缓冲头部。
         *
         * @return 完整行列表
         */
        List<String> drainLines() {
            List<String> lines = new ArrayList<>();
            synchronized (readBuffer) {
                // 在字节层面定位换行符，整段解码，避免逐字节 char 转换破坏 UTF-8 多字节字符
                ByteBuffer src = readBuffer.duplicate();
                int start = src.position();
                int limit = src.limit();
                ByteBuffer binary = src.slice();
                int from = 0;
                int len = limit - start;
                for (int i = 0; i < len; i++) {
                    if (binary.get(i) == (byte) '\n') {
                        if (i > from) {
                            byte[] chunk = new byte[i - from];
                            binary.get(from, chunk, 0, i - from);
                            lines.add(new String(chunk, StandardCharsets.UTF_8));
                        }
                        from = i + 1;
                    }
                }
                // 未完成行写回缓冲头部，保持读模式
                int tailLen = len - from;
                readBuffer.clear();
                if (tailLen > 0) {
                    byte[] tail = new byte[tailLen];
                    binary.get(from, tail, 0, tailLen);
                    readBuffer.put(tail);
                }
                readBuffer.flip();
            }
            return lines;
        }

        /**
         * 处理一行消息（虚拟线程中执行）。
         *
         * @param line 消息行
         */
        void handleLine(String line) {
            String message = line.trim();
            if (message.isEmpty()) {
                return;
            }
            int colon = message.indexOf(':');
            String topic = colon > 0 ? message.substring(0, colon) : "";
            String payload = colon > 0 ? message.substring(colon + 1) : message;
            if ("register".equals(topic)) {
                clientId = payload;
                metadata.put("clientId", payload);
                clients.put(payload, this);
                write("registered:" + payload);
                notifyListener(l -> l.onClientConnected(payload, metadata));
            } else {
                notifyListener(l -> l.onMessage(clientId, topic, payload));
            }
        }

        /**
         * 写入一行消息（入队，写事件就绪时冲刷）。
         *
         * @param payload 消息内容
         */
        void write(String payload) {
            writeQueue.offer(payload);
            if (writePending.compareAndSet(false, true)) {
                SelectionKey key = channel.keyFor(worker);
                if (key != null) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    worker.wakeup();
                } else {
                    writePending.set(false);
                }
            }
        }

        /**
         * 冲刷发送队列。
         * <p>非阻塞写一次可能写不完，剩余数据保留在 {@code pendingWrite}，随 OP_WRITE 再续写，
         * 避免数据丢失。</p>
         *
         * @param ch  通道
         * @param key 选择键
         */
        void flushQueue(SocketChannel ch, SelectionKey key) throws IOException {
            ByteBuffer buffer = pendingWrite;
            while (true) {
                if (buffer != null) {
                    int written = ch.write(buffer);
                    if (buffer.hasRemaining()) {
                        // 内核发送缓冲已满，保留剩余,等下一轮 OP_WRITE
                        return;
                    }
                    buffer = null;
                }
                String payload = writeQueue.poll();
                if (payload == null) {
                    break;
                }
                buffer = ByteBuffer.wrap((payload + "\n").getBytes(StandardCharsets.UTF_8));
            }
            pendingWrite = null;
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            writePending.set(false);
        }

        /**
         * 获取客户端标识。
         *
         * @return 客户端标识
         */
        String getClientId() {
            return clientId;
        }

        /**
         * 注销连接。
         */
        void unregister() {
            if (clientId != null) {
                clients.remove(clientId);
                notifyListener(l -> l.onClientDisconnected(clientId));
            }
            close();
        }

        /**
         * 关闭连接。
         */
        void close() {
            try {
                SelectionKey key = channel.keyFor(worker);
                if (key != null) {
                    key.cancel();
                }
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
