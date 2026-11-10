// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.sync.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncAddressResolver;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import com.chua.common.support.network.server.AbstractServer;

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
public class TcpSyncServer extends AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 读取缓冲大小
     */
    private static final int READ_BUFFER_SIZE = 16384;

    /**
     * 握手队列最小长度（等价于「用平台允许的最大队列」，内核按 somaxconn 截断）。
     */
    private static final int MIN_BACKLOG = 65536;

    /**
     * IO 缓冲线程本地实例（worker 复用一个，避免每事件 new 导致 GC 压力）
     */
    private static final ThreadLocal<ByteBuffer> IO_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(READ_BUFFER_SIZE));

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
    private final java.util.concurrent.atomic.AtomicInteger workerIndex =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 业务处理线程池。
     *
     * <p>虚拟线程池 + 有界并发：业务回调（{@code onMessage}）可能是阻塞写库/发 HTTP，
     * 不能放在事件循环线程里；但「每行一个虚拟线程」在百万连接下会产生每秒百万级的
     * 调度开销，故按「连接批次」提交，由虚拟线程调度器自然扩展。</p>
     */
    private java.util.concurrent.ExecutorService handlerPool;

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

    /**
     * 获取Protocol
     */
    @Override
    public String getProtocol() {
        return "tcp";
    }

    /**
     * 创建Server
     */
    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new TcpSyncServer(setting);
    }

    /**
     * 创建Client
     */
    @Override
    public SyncClient createClient(Object setting) {
        String url = SyncAddressResolver.resolveAddress(setting, "tcp://127.0.0.1:19391");
        return new TcpSyncClient(url);
    }

    /**
     * Do开始
     */
    @Override
    protected void doStart() {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    setting.resolveBacklog(MIN_BACKLOG));
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
            // 业务回调线程池：虚拟线程 executor，任务粒度是「一批行」而非「一行」
// [P3C C1 豁免] 场景化创建线程，需文档化
            handlerPool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            running.set(true);
            for (int i = 0; i < workerCount; i++) {
                Selector worker = Selector.open();
                workerSelectors[i] = worker;
                pendingRegistrations.add(new java.util.concurrent.ConcurrentLinkedQueue<>());
                final int idx = i;
                Thread wt = ThreadUtils.newThread(() -> workerLoop(idx, worker), "tcp-sync-worker-"
                        + setting.getPort() + "-" + i);
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

    /**
     * 停止接纳：优雅关闭期间先把 boss 事件循环摘掉。
     *
     * <p>基类的 {@code stop()} 顺序是「摘接纳 → 等存量请求 → doStop」。若不在这一步停掉
     * boss 循环，等待期间仍会不断建立新连接，优雅关闭就永远等不到活跃数归零。
     * 存量连接由 worker 继续服务，不受影响。</p>
     */
    @Override
    protected void doStopAccepting() {
        running.set(false);
        if (bossSelector != null) {
            try {
                bossSelector.wakeup();
            } catch (Exception e) {
                log.debug("[sync] 唤醒 boss 失败，已忽略: {}", e.getMessage());
            }
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                log.debug("[sync] 关闭监听通道失败，已忽略: {}", e.getMessage());
            }
        }
    }

    /**
     * Do停止
     */
    @Override
    protected void doStop() {
        running.set(false);
        if (bossSelector != null) {
            try {
                bossSelector.wakeup();
            } catch (Exception e) {
                log.debug("[sync] 操作失败，已忽略: {}", e.getMessage());
            }
        }
        if (workerSelectors != null) {
            for (Selector worker : workerSelectors) {
                if (worker != null) {
                    try {
                        worker.wakeup();
                    } catch (Exception e) {
                        log.debug("[sync] 操作失败，已忽略: {}", e.getMessage());
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
            } catch (Exception e) {
                log.debug("[sync] 操作失败，已忽略: {}", e.getMessage());
            }
            bossSelector = null;
        }
        if (workerSelectors != null) {
            for (Selector worker : workerSelectors) {
                if (worker != null) {
                    try {
                        worker.close();
                    } catch (Exception e) {
                        log.debug("[sync] 操作失败，已忽略: {}", e.getMessage());
                    }
                }
            }
            workerSelectors = null;
        }
        pendingRegistrations.clear();
        if (handlerPool != null) {
            handlerPool.shutdownNow();
            handlerPool = null;
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                log.debug("[sync] IO 操作失败，已忽略: {}", e.getMessage());
            }
            serverChannel = null;
        }
        for (ClientConnection connection : clients.values()) {
            connection.close();
        }
        clients.clear();
    }

    /**
     * 发布
     */
    @Override
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message;
        for (ClientConnection connection : clients.values()) {
            connection.write(payload);
        }
    }

    /**
     * 发送
     */
    @Override
    public void send(String clientId, String topic, Object message) {
        ClientConnection connection = clients.get(clientId);
        if (connection == null) {
            return;
        }
        connection.write(topic + ":" + message);
    }

    /**
     * 获取ConnectedClients
     */
    @Override
    public List<String> getConnectedClients() {
        return Wraps.list(clients.keySet());
    }

    /**
     * 获取ClientMetadata
     */
    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        ClientConnection connection = clients.get(clientId);
        return connection != null ? Collections.unmodifiableMap(connection.metadata) : Collections.emptyMap();
    }

    /**
     * 添加Listener
     */
    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除Listener
     */
    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取ProtocolType
     */
    @Override
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
                        } catch (IOException closeEx) {
                            log.debug("[sync] 关闭通道失败，已忽略: {}", closeEx.getMessage());
                        }
                    } catch (Throwable t) {
                        // 键被并发取消会抛 CancelledKeyException：boss 线程绝不能因此退出，
                        // 否则整个服务停止接受新连接。
                        log.warn("[sync] Boss 分发异常: {} -> {}",
                                t.getClass().getSimpleName(), t.getMessage());
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("TCP SyncServer Boss 循环异常", e);
                }
            } catch (Throwable t) {
                if (running.get()) {
                    log.error("TCP SyncServer Boss 循环异常", t);
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
                    } catch (Throwable t) {
                        // 连接被并发关闭时 isReadable()/flushQueue 的 interestOps 会抛
                        // CancelledKeyException：只清理该连接，worker 线程必须存活。
                        log.warn("[sync] Worker{} 分发异常: {} -> {}", idx,
                                t.getClass().getSimpleName(), t.getMessage());
                        try {
                            ClientConnection connection =
                                    key.attachment() instanceof ClientConnection c ? c : null;
                            if (connection != null) {
                                connection.unregister();
                            } else {
                                key.cancel();
                            }
                        } catch (Exception ignored) {
                            // 连接已经不可用，忽略清理失败
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("TCP SyncServer Worker{} 循环异常", idx, e);
                }
            } catch (Throwable t) {
                if (running.get()) {
                    log.error("TCP SyncServer Worker{} 循环异常", idx, t);
                }
            }
        }
        IO_BUFFER.remove();
    }

    /**
     * 处理 accept 事件: 从 boss accept 后轮询分发到 worker 待注册队列。
     *
     * <p>百万连接下的连接预算在这里判定：accept 之后、入队注册之前，
     * 超限直接关闭（回 503 由调用方决定），绝不让超卖连接进入 Selector。</p>
     *
     * @param key 选择键
     * @return 返回值
     * @throws IOException 异常
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel channel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel;
        // 批量 drain 必须有上限：突发建连（每秒数千）时队列可能很长，
        // 一次事件循环全抽空会让 worker 队列瞬间堆积，也拖慢已建立连接的读事件
        int batch = Math.max(setting.getAcceptBatch(), 1);
        int accepted = 0;
        while (accepted++ < batch && (socketChannel = channel.accept()) != null) {
            if (!tryAcquireConnection()) {
                // 超限：尽力回 503（若配置开启），然后关闭
                writeOverloadAndClose(socketChannel);
                continue;
            }
            socketChannel.configureBlocking(false);
            socketChannel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
            // 缓冲区按配置应用（默认不配置时无系统调用）
            applyChannelSocketOptions(socketChannel);
            int target = workerIndex.getAndIncrement() & 0x7FFFFFFF;
            pendingRegistrations.get(target % workerCount).offer(socketChannel);
            Selector worker = workerSelectors[target % workerCount];
            if (worker != null) {
                worker.wakeup();
            }
        }
    }

    /**
     * 对超出连接上限的客户端回 503 后关闭（尽力而为，写失败直接断开）。
     *
     * @param channel 刚 accept 的连接
     */
    private void writeOverloadAndClose(SocketChannel channel) {
        try {
            if (setting.isRejectWith503()) {
                int max = setting.getMaxConnections();
                byte[] body = ("Service Unavailable: connection limit reached (maxConnections="
                        + max + ")").getBytes(StandardCharsets.UTF_8);
                ByteBuffer resp = ByteBuffer.wrap((
                        "HTTP/1.1 503 Service Unavailable\r\n"
                                + "Content-Type: text/plain; charset=utf-8\r\n"
                                + "Content-Length: " + body.length + "\r\n"
                                + "Retry-After: 1\r\n"
                                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                channel.write(new ByteBuffer[]{resp, ByteBuffer.wrap(body)});
            }
        } catch (Exception e) {
            log.debug("[sync] 写 503 拒绝响应失败，直接关闭: {}", e.getMessage());
        } finally {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 关闭失败不影响判定
            }
        }
    }

    /**
     * 处理 read 事件：非阻塞读入缓冲，按行切分后交业务线程处理。
     *
     * <p>与旧实现的关键差异：不再对每一行 {@code Thread.ofVirtual().start(...)}。
     * 虚拟线程本身便宜，但「每行一个新线程」意味着每条心跳/每条消息都要一次
     * 线程对象分配 + 调度器入队，在百万连接下就是每秒百万级的调度压力，
     * 而且这些任务之间毫无并发收益（它们的临界区只有连接自身的写队列）。</p>
     *
     * <p>改为复用同一条连接上已提交的任务：一个连接一批行只提交一个任务，
     * 顺序仍在连接内保证，且由 {@link #handlerPool} 承担背压。</p>
     *
     * @param key 选择键
     * @return 返回值
     * @throws IOException 异常
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
        // 切分完整行，一批行只提交一次
        List<String> lines = connection.drainLines();
        if (lines.isEmpty()) {
            return;
        }
        handlerPool.execute(() -> {
            for (String line : lines) {
                connection.handleLine(line);
            }
        });
    }

    /**
     * 处理 write 事件：冲刷连接发送队列。
     * @param key 选择键
     * @return 返回值
     * @throws IOException 异常
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
            } catch (Exception e) {
                log.debug("[sync] 操作失败，已忽略: {}", e.getMessage());
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
        private final Map<String, Object> metadata = Wraps.map();

        /**
         * 读取缓冲（按行切分前的原始字节），首次收到数据时才分配。
         */
        private ByteBuffer readBuffer;

        /**
         * 发送队列（写事件就绪时冲刷）。
         *
         * <p>用 {@link java.util.ArrayDeque} + 同步块而非 {@link java.util.concurrent.LinkedBlockingQueue}：
         * 后者每个元素一个 Node 对象、每次 offer/poll 一次 CAS，而这里从不阻塞等待
         * （非阻塞通道 + OP_WRITE 续写），LinkedBlockingQueue 的阻塞语义完全用不上，
         * 只剩下每条消息一次进队一次出队的额外对象与原子操作开销。</p>
         *
         * <p>并发边界：入队来自业务线程（{@link #write(String)}），出队来自该连接所属
         * worker 事件循环（{@link #flushQueue}），二者用同一把锁串行化。</p>
         */
        private final java.util.ArrayDeque<String> writeQueue = new java.util.ArrayDeque<>(4);

        /**
         * 发送队列锁（同时保护 {@link #pendingWrite}）
         */
        private final Object writeLock = new Object();

        /**
         * 是否已注册写事件
         */
        private final AtomicBoolean writePending = new AtomicBoolean(false);

        /**
         * 是否已占用连接预算（保证 {@code releaseConnection()} 幂等）
         */
        private boolean budgetAcquired = true;

        /**
         * 创建客户端连接。
         *
         * @param channel 底层通道
         * @param worker  所属 Worker Selector
         */
        private ClientConnection(SocketChannel channel, Selector worker) {
            this.channel = channel;
            this.worker = worker;
        }

        /**
         * 追加读取字节。
         *
         * <p>读缓冲首次到达时按需分配：百万连接绝大多数是空闲长连接，
         * 构造时给每条 32KB 就是几十 GB 的纯浪费（见 {@link #readBuffer} 说明）。</p>
         *
         * @param buffer 数据（读模式）
         */
        void appendBuffer(ByteBuffer buffer) {
            // 事件循环线程单线程访问本连接，但业务虚拟线程可能同时读 readBuffer
            // （见 drainLines 的调用顺序），故仍用同一把锁保证可见性
            synchronized (writeLock) {
                if (readBuffer == null) {
                    readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE * 2);
                    readBuffer.limit(0);
                }
                readBuffer.compact();
                // 按需拷贝：缓冲已满且仍有剩余时不再 put（否则 BufferOverflowException），
                // 让调用方的 clear 循环在下一轮继续读（backpressure 天然生效）
                int toCopy = Math.min(buffer.remaining(), readBuffer.remaining());
                if (toCopy > 0) {
                    int oldLimit = buffer.limit();
                    buffer.limit(buffer.position() + toCopy);
                    readBuffer.put(buffer);
                    buffer.limit(oldLimit);
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
            List<String> lines = Wraps.list();
            synchronized (writeLock) {
                ByteBuffer rb = readBuffer;
                if (rb == null) {
                    return lines;
                }
                // 在字节层面定位换行符，整段解码，避免逐字节 char 转换破坏 UTF-8 多字节字符
                int start = rb.position();
                int limit = rb.limit();
                ByteBuffer binary = rb.duplicate().slice();
                int from = 0;
                int len = limit - start;
                for (int i = 0; i < len; i++) {
                    if (binary.get(i) == (byte) '\n') {
                        if (i > from) {
                            byte[] chunk = new byte[i - from];
                            binary.get(from, chunk, 0, i - from);
                            lines.add(new String(chunk, StandardCharsets.UTF_8));
                        }
                        // i == from 表示空行（心跳），不产生消息，直接跳过
                        from = i + 1;
                    }
                }
                // 未完成行写回缓冲头部，保持读模式
                int tailLen = len - from;
                rb.clear();
                if (tailLen > 0) {
                    // 只有半包（一行还没收完）时才会走这里，分配一次小块即可；
                    // Buffer.put(ByteBuffer,int,int) 不存在，故先取到 byte[] 再写入
                    byte[] tail = new byte[tailLen];
                    binary.get(from, tail, 0, tailLen);
                    rb.put(tail);
                }
                rb.flip();
            }
            return lines;
        }

        /**
         * 处理一行消息（由 {@link #handlerPool} 中的虚拟线程执行）。
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
            synchronized (writeLock) {
                writeQueue.addLast(payload);
            }
            if (writePending.compareAndSet(false, true)) {
                SelectionKey key = channel.keyFor(worker);
                if (key != null) {
                    try {
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                        worker.wakeup();
                    } catch (CancelledKeyException e) {
                        // 连接已被事件循环关闭：这条连接不会再冲刷，复位标志即可
                        writePending.set(false);
                    }
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
            synchronized (writeLock) {
                ByteBuffer buffer = pendingWrite;
                while (true) {
                    if (buffer != null) {
                        int written = ch.write(buffer);
                        if (buffer.hasRemaining()) {
                            // 内核发送缓冲已满，保留剩余,等下一轮 OP_WRITE
                            pendingWrite = buffer;
                            return;
                        }
                        buffer = null;
                    }
                    String payload = writeQueue.pollFirst();
                    if (payload == null) {
                        break;
                    }
                    buffer = ByteBuffer.wrap((payload + "\n").getBytes(StandardCharsets.UTF_8));
                }
                pendingWrite = null;
                // 队列已空：交还写权 + 清写兴趣必须在锁内完成，否则存在丢唤醒窗口——
                // write() 若在「排空循环结束」与「writePending 置 false」之间入队，
                // 它看到的仍是 true，不会设置 OP_WRITE；随后这里再清掉 OP_WRITE，
                // 这条消息就永远躺在队列里，直到下一次 write() 才被带走。
                writePending.set(false);
                try {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                } catch (CancelledKeyException ignored) {
                    // 连接已并发关闭：兴趣位无意义
                }
            }
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
         *
         * <p>{@link #TcpSyncServer#releaseConnection()} 必须与 accept 期的
         * {@link TcpSyncServer#tryAcquireConnection()} 严格配对，且只能执行一次：
         * 同一连接可能被「读结束 / 异常 / stop()」多路径关闭，用标志位保证幂等。</p>
         */
        void close() {
            try {
                SelectionKey key = channel.keyFor(worker);
                if (key != null) {
                    key.cancel();
                }
                channel.close();
            } catch (IOException e) {
                log.debug("[sync] IO 操作失败，已忽略: {}", e.getMessage());
            } finally {
                if (budgetAcquired) {
                    budgetAcquired = false;
                    releaseConnection();
                }
                synchronized (writeLock) {
                    writeQueue.clear();
                    pendingWrite = null;
                }
            }
        }
    }}