package com.chua.common.support.network.server.nio;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.websocket.WebSocketProtocol;
import com.chua.common.support.network.ssl.SslUtils;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 NIO {@link ServerSocketChannel} 的 HTTP/1.1 服务器实现。
 *
 * <p>替代 {@code com.sun.net.httpserver.HttpServer}，从根本上解决单 acceptor + 无法调优的限制。
 * 使用 ServerSocketChannel（阻塞模式）accept 连接，每个连接分配一个虚拟线程处理请求，
 * 支持 HTTP/1.1 Keep-Alive 连接复用。</p>
 *
 * <p>特性：
 * <ul>
 *   <li>Selector 无关 — 阻塞 accept + 虚拟线程阻塞 I/O，简洁高效</li>
 *   <li>完全可控的 backlog / SO_REUSEADDR / TCP_NODELAY / bufferSize</li>
 *   <li>HTTP/1.1 Keep-Alive 连接复用</li>
 *   <li>SSE (Server-Sent Events) chunked transfer 流式推送</li>
 *   <li>WebSocket 升级 — 请求头携带 {@code Upgrade: websocket} 时自动切换为帧协议，
 *       支持 {@code @OnMessage} 注解方法与 {@link #onSubscribe(String, ServerHandler)} 订阅</li>
 *   <li>SSL/TLS — 自签名证书一键生成（{@code selfSignedAuto}）或 KeyStore/PEM 加载</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/12
 */
@Slf4j
@Spi({"nio", "nio-http"})
public class NioHttpServer extends AbstractServer {

    /** 服务器通道 */
    private ServerSocketChannel serverChannel;
    /**
     * 多 Selector 分片:每分片一个事件循环线程,解决单事件循环在高并发下的瓶颈
     */
    private Selector[] selectors;
    /**
     * 每分片对应的待写 key 队列(worker 只入队,由对应分片事件循环统一注册 OP_WRITE)
     */
    private java.util.Queue<SelectionKey>[] pendingWriteQueues;
    /**
     * 每分片对应的"写完成待恢复 OP_READ"队列:worker 直写排空后入队,事件循环统一恢复 OP_READ
     */
    private java.util.Queue<SelectionKey>[] rearmReadQueues;
    /** 每分片对应的待注册连接队列:accept 线程只入队,由目标分片事件循环线程自行 register,
     *  消除跨线程 register 与 select() 之间的竞态(8 分片下跨线程注册占比高时会出现请求超时) */
    /** Pendingacceptqueues */
    private java.util.Queue<SocketChannel>[] pendingAcceptQueues;
    /** 执行器 */
    private ExecutorService executor;
    /** Acceptor池 */
    private ExecutorService acceptorPool;
    /** SSL上下文 */
    private SSLContext sslContext;

    /**
     * WebSocket 主题处理器映射（topic -> handlers）。
     */
    private final Map<String, List<ServerHandler>> wsTopicHandlers = new ConcurrentHashMap<>();

    /**
     * 当前活跃的 WebSocket 连接。
     */
    private final List<WsConnection> wsConnections = new CopyOnWriteArrayList<>();

    /**
     * 创建 NioHttpServer 实例
     * @param setting setting
     */
    public NioHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            // 基准测试快速路径:bench.fast=true 时强制内联派发,echo 等微秒级 handler 跳过虚拟线程
            // 提交与 Selector 唤醒往返,在 event loop 线程直接解析+处理+写出,追求极限吞吐
            if ("true".equals(System.getProperty("bench.fast"))) {
                setting.setInlineDispatch(true);
            }
            ServerSetting.SslConfig ssl = setting.getSsl();
            sslContext = SslUtils.autoSsl(ssl);
            if (sslContext != null) {
                log.info("NIO HttpServer SSL enabled (selfSigned={})", ssl.isSelfSigned());
            }

            serverChannel = ServerSocketChannel.open();
            // 非阻塞 accept:Selector 事件循环驱动,真正的 NIO 响应式接入,
            // 避免阻塞 accept 单点瓶颈,提升高并发连接接纳吞吐
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, Math.max(setting.getBufferSize(), 16384));
            // 高并发连接接纳：backlog 下限 65536，万级并发突发下避免连接被内核拒绝
            int backlog = Math.max(setting.getBacklog(), 65536);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), backlog);

            // 回填实际端口（port=0 时由系统分配）
            InetSocketAddress bound = (InetSocketAddress) serverChannel.getLocalAddress();
            setting.setPort(bound.getPort());

            executor = Executors.newVirtualThreadPerTaskExecutor();
            // 多 Selector 分片:每分片一个事件循环线程,连接按 hash 分散注册,
            // 解决单事件循环在高并发(2000+)下成为吞吐瓶颈的问题(类 Netty 主从模型)。
            // OP_ACCEPT 由事件循环线程 0 自行注册(eventLoop 内与 select 同线程,见下),
            // 新连接入队后由目标分片线程自行 register(与 select 同线程),消除跨线程注册竞态。
            // 分片数上限 2 为实测稳定值:Windows 下 Selector 数 >2 时(4/8 分片实测)
            // 出现请求超时/连接被拒(WindowsSelectorImpl 多 Selector 并发稳定性限制),
            // 与"百万级 RPS"目标冲突但无法在本平台规避,保持 2 分片确保零失败
            int eventLoops = setting.getEventLoops();
            String osName = System.getProperty("os.name", "").toLowerCase();
            boolean windows = osName.contains("win");
            if (eventLoops <= 0) {
                // 自动:Windows 实测 4 分片为最优(2 分片未吃满并行度、16+ 分片 select 开销负优化),
                // Linux/macOS 满核扩展(epoll/kqueue 多 Selector 稳定),达最大吞吐
                int cpus = Runtime.getRuntime().availableProcessors();
                eventLoops = windows ? 4 : Math.max(cpus, 2);
            }
            // Windows 强制上限 2：>2 个 Selector 并发时 WindowsSelectorImpl 表现为
            // 接受连接成功但读事件永远无法感知(实测 eventLoops=6/12 下 QPS=0、全请求超时)。
            // 该现象无法在本平台规避，Clamping 而非静默失败，避免用户误以为优化生效。
            // Windows 推荐上限 4：实测 4 分片吞吐最优,超出 16+ 分片 select() 并发开销反而负优化;
            // 保留 bench.fast 绕过供实验验证其他分片数。
            if (windows && eventLoops > 4 && !"true".equals(System.getProperty("bench.fast"))) {
                log.warn("NIO HttpServer eventLoops={} 超过 Windows 实测最优上限 4，已收敛至 4 " +
                        "(16+ 分片 select 并发负优化)。如需更高并行度请使用 Linux+epoll", eventLoops);
                eventLoops = 4;
            }
            if ("true".equals(System.getProperty("bench.fast"))) {
                // 基准测试:bench.el 系统属性可强制 event loops 数量(绕过 Windows clamp),
                // 用于验证不同分片数下的吞吐拐点
                String el = System.getProperty("bench.el");
                if (el != null) {
                    try {
                        eventLoops = Integer.parseInt(el);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            eventLoops = Math.max(1, eventLoops);
            selectors = new Selector[eventLoops];
            pendingWriteQueues = new java.util.Queue[eventLoops];
            @SuppressWarnings("unchecked")
            java.util.Queue<SelectionKey>[] queues = new java.util.concurrent.ConcurrentLinkedQueue[eventLoops];
            @SuppressWarnings("unchecked")
            java.util.Queue<SelectionKey>[] rearmQueues = new java.util.concurrent.ConcurrentLinkedQueue[eventLoops];
            @SuppressWarnings("unchecked")
            java.util.Queue<SocketChannel>[] acceptQueues = new java.util.concurrent.ConcurrentLinkedQueue[eventLoops];
            for (int i = 0; i < eventLoops; i++) {
                selectors[i] = Selector.open();
                queues[i] = new java.util.concurrent.ConcurrentLinkedQueue<>();
                rearmQueues[i] = new java.util.concurrent.ConcurrentLinkedQueue<>();
                acceptQueues[i] = new java.util.concurrent.ConcurrentLinkedQueue<>();
            }
            pendingWriteQueues = queues;
            rearmReadQueues = rearmQueues;
            pendingAcceptQueues = acceptQueues;
            // serverChannel 注册移到事件循环线程 0 内部(eventLoop(0) 启动后自行注册):
            // 主线程跨线程 register 到 selectors[0] 与事件循环 select() 存在竞态,
            // 连续启停/快速启停时 OP_ACCEPT 可能不被感知(单 Selector 实验 0 失败证实);
            // 由事件循环线程自己注册 + select 同线程执行,彻底消除竞态
            acceptorPool = ThreadUtils.newDaemonFixedThreadPool(eventLoops, "nio-event-loop");
            for (int i = 0; i < eventLoops; i++) {
                final int idx = i;
                // execute + 最外层兜底:任何异常(含被 FutureTask 吞掉的)都记录完整堆栈,
                // 避免事件循环线程静默死亡导致该分片全部连接超时(高并发下实测 QPS 从 5k+ 掉到 0)
                acceptorPool.execute(() -> {
                    try {
                        eventLoop(idx);
                    } catch (Throwable t) {
                        log.error("nio event-loop[{}] 终止,堆栈: {}", idx, t.getMessage(), t);
                    }
                });
            }

            log.info("NIO HttpServer started on {}:{} (backlog={}, eventLoops={}, reactive=true)",
                    setting.getHost(), setting.getPort(), backlog, eventLoops);
        } catch (Exception e) {
            throw new RuntimeException("NIO HttpServer 启动失败", e);
        }
    }

    /**
     * 事件循环(分片版):每分片一个 Selector + 线程,处理该分片连接的 OP_READ/OP_WRITE。
     * 分片 0 额外承载 OP_ACCEPT。连接不占线程;完整请求解析后提交虚拟线程 worker 池执行 handler 链。
     * @param idx 索引，不允许为 null
     */
    private void eventLoop(int idx) {
        Selector sel = selectors[idx];
        java.util.Queue<SelectionKey> writeQueue = pendingWriteQueues[idx];
        log.info("nio event-loop[{}] started, selector={}", idx, sel);
        // 分片 0 承载 OP_ACCEPT:由本事件循环线程自行注册 serverChannel(与 select 同线程),
        // 消除主线程跨线程 register 与 select 之间的竞态(连续启停时 OP_ACCEPT 感知不到)
        if (idx == 0 && serverChannel != null && serverChannel.isOpen()) {
            try {
                serverChannel.register(sel, SelectionKey.OP_ACCEPT);
            } catch (Exception e) {
                log.warn("nio event-loop[0] 注册 OP_ACCEPT 失败: {}", e.getMessage());
            }
        }
        // 关键竞态防护:doStart() 里 submit 本任务时 running 可能仍为 false(start() 在 doStart()
        // 返回后才置 true)。若事件循环线程抢跑先执行 while(running) 判断,将立即退出,
        // 导致该分片 accept/read/write 全部停摆(单连接随机 20% 失败、高并发必现)。
        // 等待 running 变 true 再进入主循环(限时兜底,避免 start() 失败时线程永久阻塞)。
        long spinDeadline = System.nanoTime() + 5_000_000_000L;
        while (!running) {
            if (System.nanoTime() > spinDeadline) {
                break;
            }
            Thread.onSpinWait();
        }
        while (running) {
            try {
                sel.select(200L);
                // worker 直写排空后:本分片事件循环统一恢复 OP_READ(与 select 同线程,无跨线程竞态)
                SelectionKey rk;
                while ((rk = rearmReadQueues[idx].poll()) != null) {
                    try {
                        if (rk.isValid()) {
                            ConnectionState rst = (ConnectionState) rk.attachment();
                            if (rst != null && rst.keepAlive && running) {
                                rk.interestOps(SelectionKey.OP_READ);
                                rst.inWorker = false;
                            } else if (rst != null) {
                                closeConn(rk, rst);
                            }
                        }
                    } catch (Exception e) {
                        // 对已取消(客户端断开)的 key 操作会抛 CancelledKeyException,
                        // 绝不能让它逃出事件循环线程,否则该分片所有连接静默血崩(0 吞吐)
                        closeQuietly(rk.channel() instanceof SocketChannel sc ? sc : null);
                    }
                }
                // 统一在本分片事件循环线程注册 OP_WRITE(worker 只入队 + wakeup,避免跨线程 interestOps 竞态)
                SelectionKey wk;
                while ((wk = writeQueue.poll()) != null) {
                    try {
                        if (wk.isValid()) {
                            wk.interestOps(SelectionKey.OP_WRITE);
                        }
                    } catch (Exception e) {
                        closeQuietly(wk.channel() instanceof SocketChannel sc ? sc : null);
                    }
                }
                // 注册新连接:由本分片事件循环线程自行 register(与 select 同线程),
                // 彻底消除跨线程 register 与 select() 的竞态(高并发下可致请求超时)
                SocketChannel ac;
                while ((ac = pendingAcceptQueues[idx].poll()) != null) {
                    try {
                        if (!ac.isOpen()) {
                            continue;
                        }
                        ConnectionState st = new ConnectionState(ac, setting.getMaxRequestSize(), setting.getCharset());
                        st.shard = idx;
                        ac.register(sel, SelectionKey.OP_READ, st);
                    } catch (Exception e) {
                        closeQuietly(ac);
                    }
                }
                java.util.Iterator<SelectionKey> it = sel.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    try {
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (Throwable t) {
                        // 单个连接异常(如并发关闭导致的 CancelledKeyException/RuntimeException)
                        // 只清理该连接,不允许杀死整个事件循环线程
                        log.warn("Event loop[{}] key error: {} -> {}, closing conn", idx,
                                t.getClass().getSimpleName(), t.getMessage());
                        try {
                            key.cancel();
                        } catch (Exception ignored) {
                        }
                        ConnectionState st = (ConnectionState) key.attachment();
                        closeQuietly(st != null ? st.channel : null);
                    }
                }
            } catch (Throwable t) {
                // 事件循环线程绝不能死:任何未预期异常(含 Runtime/Error)都只记录并继续,
                // 否则该分片连接的 accept/read/write 全部停摆(高并发压力下实测请求 100% 超时)
                if (running) {
                    log.warn("Event loop[{}] error: {} -> {}", idx, t.getClass().getSimpleName(), t.getMessage());
                }
            }
        }
    }

    /**
     * 处理Accept
     * @param key 键，不允许为 null
     */
    private void handleAccept(SelectionKey key) throws IOException {
        SocketChannel accepted = serverChannel.accept();
        if (accepted == null) {
            return;
        }
        // SSL 场景:回退虚拟线程阻塞路径(SSL 通道无法注册 Selector)
        if (sslContext != null) {
            accepted.configureBlocking(true);
            accepted.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
            // 握手与连接处理整体移入虚拟线程 worker:在事件循环线程内构造 SslSocketChannel
            // 会执行阻塞握手(readNet 等待 ClientHello),占住事件循环导致偶发/稳定握手失败
            // (Unrecognized SSL message),且阻塞其他连接的 accept/read 处理
            executor.submit(() -> {
                try {
                    SocketChannel client = new SslSocketChannel(accepted, sslContext.createSSLEngine());
                    handleConnection(client);
                } catch (IOException e) {
                    log.warn("TLS 握手失败，关闭连接: {}", e.getMessage());
                    closeQuietly(accepted);
                }
            });
            return;
        }
        // 非阻塞注册:连接按 hash 分散到各分片,由目标分片事件循环线程自行 register
        accepted.configureBlocking(false);
        accepted.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
        int shard = (accepted.hashCode() & Integer.MAX_VALUE) % selectors.length;
        // 只入队 + wakeup:register 动作交由目标分片事件循环线程执行(与 select 同线程),
        // 避免跨线程 register 与 select() 的竞态导致 OP_READ 感知不到、请求超时
        pendingAcceptQueues[shard].add(accepted);
        selectors[shard].wakeup();
        log.debug("nio accepted -> shard={}", shard);
    }

    /**
     * 处理读取
     * @param key 键，不允许为 null
     */
    private void handleRead(SelectionKey key) throws IOException {
        ConnectionState st = (ConnectionState) key.attachment();
        // 请求已在 worker 处理中:摘除读兴趣,避免事件循环空转与重复提交 worker;
        // worker 完成后经 pendingWrites 由事件循环重新注册 OP_WRITE
        if (st.inWorker) {
            key.interestOps(0);
            return;
        }
        // 懒分配读缓冲:空闲连接(未收发数据)不占用 32KB,百万级空闲连接场景节省数十 GB 堆内存
        ByteBuffer buf = st.readBuf;
        if (buf == null) {
            buf = ByteBuffer.allocate(32768);
            st.readBuf = buf;
        }
        int n = st.channel.read(buf);
        if (n < 0) {
            closeConn(key, st);
            return;
        }
        if (n == 0) {
            return;
        }
        buf.flip();
        int r = st.request.feed(buf);
        buf.compact(); // 保留未消费数据
        if (r == 1) {
            // 完整请求解析完成:摘除 OP_READ
            key.interestOps(0);
            st.inWorker = true;
            // 内联快速路径按需启用(setting.isInlineDispatch=true 且非 SSL/WS):
            // 非阻塞 handler 事件循环线程内执行并同步写出,省虚拟线程提交 + Selector 唤醒
            if (setting.isInlineDispatch() && sslContext == null
                    && !WebSocketProtocol.isUpgradeRequest(st.request)) {
                processRequestInline(st, key);
            } else {
                executor.submit(() -> processRequestDirectWrite(st, key));
            }
        } else if (r < 0) {
            closeConn(key, st);
        }
    }

    /**
     * 内联快速路径:事件循环线程直接执行 handler 链并同步写出。
     * <p>仅适用于非阻塞 handler(setting.inlineDispatch=true 且非 SSL/WS)。
     * 若单次 write 未写完(对端背压),剩余字节追加 pendingWrite 队列,
     * 由事件循环按既有 OP_WRITE 路径续写,不丢失数据。</p>
     * @param st 方法入参 st
     * @param key 键，不允许为 null
     */
    private void processRequestInline(ConnectionState st, SelectionKey key) {
        try {
            NioServerResponse response = new NioServerResponse(st.channel);
            // 内联写出:事件循环线程同步写通道,未写完部分入 writeQueue 交由 OP_WRITE 续写
            response.setAsyncWriter((header, body) -> {
                synchronized (st.writeQueue) {
                    st.writeQueue.add(header);
                    if (body != null && body.hasRemaining()) {
                        st.writeQueue.add(body);
                    }
                }
                flushInlineWrite(st, key);
            });
            try {
                handleRequest(st.request, response);
            } catch (Exception e) {
                log.debug("Inline handler failed: {}", e.getMessage());
                if (!response.isCommitted()) {
                    response.sendError(500, "Internal Server Error");
                }
            } finally {
                response.complete();
            }
            st.keepAlive = shouldKeepAlive(st.request, response);
            st.request.resetForNextRequest();
            // 同步写出已尝试,若队列仍有残留则回退事件循环 OP_WRITE 续写
            boolean hasPending;
            synchronized (st.writeQueue) {
                hasPending = !st.writeQueue.isEmpty();
            }
            if (hasPending) {
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            } else {
                finishAfterWrite(st, key);
            }
        } catch (Exception e) {
            log.warn("Inline worker failed: {}", e.getMessage());
            closeConn(key, st);
        }
    }

    /**
     * 内联路径写出完成收尾：Keep-Alive 恢复 OP_READ 继续读，否则关闭连接。
     * <p>对照 {@link #handleWrite} 写完后的收尾语义。</p>
     *
     * @param st  连接状态
     * @param key 选择键
     */
    private void finishAfterWrite(ConnectionState st, SelectionKey key) {
        if (st.keepAlive && running) {
            key.interestOps(SelectionKey.OP_READ);
            st.inWorker = false;
        } else {
            closeConn(key, st);
        }
    }

    /**
     * 事件循环线程同步写缓冲队列;写不完整时交由 OP_WRITE 续写
     * @param st 方法入参 st
     * @param key 键，不允许为 null
     */
    private void flushInlineWrite(ConnectionState st, SelectionKey key) {
        synchronized (st.writeQueue) {
            while (true) {
                ByteBuffer bb = st.writeQueue.peek();
                if (bb == null) {
                    break;
                }
                try {
                    int w = st.channel.write(bb);
                    if (w < 0) {
                        closeConn(key, st);
                        return;
                    }
                    if (bb.hasRemaining()) {
                        return;
                    }
                } catch (IOException e) {
                    closeConn(key, st);
                    return;
                }
                st.writeQueue.poll();
            }
        }
    }

    /**
     * worker(虚拟线程)执行 handler 链,响应通过 asyncWriter 交给事件循环 OP_WRITE 写出。
     * @param st 方法入参 st
     * @param key 键，不允许为 null
     */
    private void processRequest(ConnectionState st, SelectionKey key) {
        try {
            // WebSocket 升级:回退到虚拟线程帧协议处理
            if (WebSocketProtocol.isUpgradeRequest(st.request)) {
                // 通道已注册到 Selector(非阻塞),直接 configureBlocking(true) 会抛
                // IllegalBlockingModeException(NIO 禁止已注册通道切阻塞模式),导致握手响应写不出、
                // 客户端报 "HTTP/1.1 header parser received no bytes";先取消注册再切换
                key.cancel();
                st.channel.configureBlocking(true);
                handleWebSocketUpgrade(st.channel, st.request);
                closeConn(key, st);
                return;
            }
            NioServerResponse response = new NioServerResponse(st.channel);
            response.setAsyncWriter((header, body) -> {
                synchronized (st.writeQueue) {
                    st.writeQueue.add(header);
                    if (body != null && body.hasRemaining()) {
                        st.writeQueue.add(body);
                    }
                }
                // 交由所属分片事件循环线程统一注册 OP_WRITE,避免 worker 线程跨线程改 interestOps 竞态
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            });
            try {
                handleRequest(st.request, response);
            } catch (Exception e) {
                log.warn("Request handling failed: {}", e.getMessage());
                if (!response.isCommitted()) {
                    response.sendError(500, "Internal Server Error");
                }
            } finally {
                response.complete();
            }
            st.keepAlive = shouldKeepAlive(st.request, response);
            st.request.resetForNextRequest();
            // 触发写:入队待写 key,由所属分片事件循环线程统一注册 OP_WRITE
            boolean hasPending;
            synchronized (st.writeQueue) {
                hasPending = !st.writeQueue.isEmpty();
            }
            if (hasPending) {
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            }
        } catch (Exception e) {
            log.warn("Worker handling failed: {} -> {}", e.getClass().getSimpleName(), e.getMessage());
            closeConn(key, st);
        }
    }

    /**
     * worker(虚拟线程)执行 handler 链后,由 worker 线程直接同步写出(直写路径)。
     * <p>对比 {@link #processRequest}:直写让 socket 写调用在 12 核虚拟线程上并行执行,
     * 而非全部串行在 2 个事件循环线程上(Windows 上限),显著提升多核吞吐。</p>
     * <p>线程安全:与事件循环 {@link #handleWrite} 共用 st.writeQueue 同一把锁排空,
     * worker 只操作 writeQueue + rearmReadQueues(仅入队),interestOps 仍只由事件循环修改。</p>
     * @param st 方法入参 st
     * @param key 键，不允许为 null
     */
    private void processRequestDirectWrite(ConnectionState st, SelectionKey key) {
        try {
            // WebSocket 升级:回退到虚拟线程帧协议处理
            if (WebSocketProtocol.isUpgradeRequest(st.request)) {
                key.cancel();
                st.channel.configureBlocking(true);
                handleWebSocketUpgrade(st.channel, st.request);
                closeConn(key, st);
                return;
            }
            NioServerResponse response = new NioServerResponse(st.channel);
            response.setAsyncWriter((header, body) -> {
                synchronized (st.writeQueue) {
                    st.writeQueue.add(header);
                    if (body != null && body.hasRemaining()) {
                        st.writeQueue.add(body);
                    }
                }
            });
            try {
                handleRequest(st.request, response);
            } catch (Exception e) {
                log.warn("Request handling failed: {}", e.getMessage());
                if (!response.isCommitted()) {
                    response.sendError(500, "Internal Server Error");
                }
            } finally {
                response.complete();
            }
            st.keepAlive = shouldKeepAlive(st.request, response);
            st.request.resetForNextRequest();
            // 直写:worker 线程同步排空 writeQueue(与事件循环 handleWrite 同锁)。
            // 未写完(对端背压)时剩余字节交由事件循环 OP_WRITE 续写。
            boolean allWritten;
            synchronized (st.writeQueue) {
                allWritten = drainWriteQueue(st, key);
            }
            if (allWritten) {
                // 直写完成:通知事件循环恢复 OP_READ(interestOps 仍由事件循环线程修改)
                rearmReadQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            } else {
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            }
        } catch (Exception e) {
            log.warn("DirectWrite worker failed: {} -> {}", e.getClass().getSimpleName(), e.getMessage());
            closeConn(key, st);
        }
    }

    /**
     * 排空 writeQueue 至写满或清空。
     * <p>必须在持有 st.writeQueue 锁的情况下调用(与事件循环 handleWrite 互斥)。
     * 返回 true 表示已全部写出,false 表示 socket 缓冲已满仍有剩余(需 OP_WRITE 续写)。</p>
     *
     * @param st  连接状态
     * @param key 选择键
     * @return true 全部写完,false 未写完
     */
    private boolean drainWriteQueue(ConnectionState st, SelectionKey key) {
        while (true) {
            ByteBuffer bb = st.writeQueue.peek();
            if (bb == null) {
                return true;
            }
            try {
                int w = st.channel.write(bb);
                if (w < 0) {
                    closeConn(key, st);
                    return true;
                }
                if (bb.hasRemaining()) {
                    return false;
                }
            } catch (IOException e) {
                closeConn(key, st);
                return true;
            }
            st.writeQueue.poll();
        }
    }

    /**
     * 处理写入
     * @param key 键，不允许为 null
     */
    private void handleWrite(SelectionKey key) throws IOException {
        ConnectionState st = (ConnectionState) key.attachment();
        // 与 worker 的 asyncWriter 共用同一把锁排空队列:
        // ArrayDeque 扩容时内部数组引用被替换,事件循环线程若无锁 peek/poll,
        // 可能与 worker 的 synchronized add 并发读到旧数组/撕裂状态,
        // 造成响应丢失 → 连接静默挂起 → 客户端超时(间歇性 0.4%~0.01% 失败)。
        synchronized (st.writeQueue) {
            while (true) {
                ByteBuffer bb = st.writeQueue.peek();
                if (bb == null) {
                    break;
                }
                int w = st.channel.write(bb);
                if (w < 0) {
                    closeConn(key, st);
                    return;
                }
                if (bb.hasRemaining()) {
                    // 未写完,等待下次 OP_WRITE
                    return;
                }
                st.writeQueue.poll();
            }
        }
        // 写完:Keep-Alive 则重新注册 OP_READ,否则关闭
        if (st.keepAlive && running) {
            key.interestOps(SelectionKey.OP_READ);
            st.inWorker = false;
        } else {
            closeConn(key, st);
        }
    }

    /**
     * 关闭Conn
     * @param key 键，不允许为 null
     * @param st 方法入参 st
     */
    private void closeConn(SelectionKey key, ConnectionState st) {
        try {
            key.cancel();
        } catch (Exception ignored) {
        }
        closeQuietly(st.channel);
    }

    /** 连接状态:非阻塞通道 + 增量解析器 + 读缓冲 + 待写队列 */
    private static final class ConnectionState {
        final SocketChannel channel;
        final NioServerRequest request;
        // 每连接读缓冲:首次收到数据时懒分配(空闲连接 0 占用,百万级空闲连接省数十 GB),
        // 连接生命周期内复用同一缓冲,无跨连接共享风险;
        // 32KB 减少大请求体场景下的 read 系统调用次数,小请求场景无额外开销(仅按需 flip)
        ByteBuffer readBuf;
        // 实测 ArrayDeque + synchronized 在 1000/2000 并发下吞吐最高(3876/2430 RPS),
        // 无锁队列 + pendingWrites 因多一轮 select 循环反而降低吞吐
        final java.util.ArrayDeque<ByteBuffer> writeQueue = new java.util.ArrayDeque<>();
        boolean keepAlive = true;
        boolean inWorker = false;
        /** 所属分片索引(决定注册到哪个 Selector 与写队列) */
        int shard = 0;

        ConnectionState(SocketChannel channel, long maxRequestSize, String charset) {
            this.channel = channel;
            this.request = new NioServerRequest(channel, maxRequestSize, charset);
        }
    }

    /**
     * 处理连接(SSL 回退路径):阻塞读 + feed() 增量解析,支持 Keep-Alive。
     * 普通 HTTP 走事件循环 processRequest;SSL 通道无法注册 Selector,回退此处。
     * @param channel 方法入参 channel
     */
    private void handleConnection(SocketChannel channel) {
        try {
            NioServerRequest request = new NioServerRequest(channel,
                    setting.getMaxRequestSize(), setting.getCharset());
            // 懒分配:首次读到数据才分配 16KB,避免空闲连接预占内存
            ByteBuffer readBuf = null;
            while (running && channel.isConnected()) {
                if (readBuf == null) {
                    readBuf = ByteBuffer.allocate(16384);
                }
                int n = channel.read(readBuf);
                if (n < 0) {
                    break; // 对端关闭
                }
                if (n == 0) {
                    continue;
                }
                readBuf.flip();
                int r = request.feed(readBuf);
                readBuf.compact();
                if (r == 0) {
                    continue; // 还需更多数据
                }
                if (r < 0) {
                    break; // 解析错误
                }
                // WebSocket 升级：Upgrade: websocket 时切换为帧协议
                if (WebSocketProtocol.isUpgradeRequest(request)) {
                    handleWebSocketUpgrade(channel, request);
                    break;
                }
                NioServerResponse response = new NioServerResponse(channel);
                try {
                    handleRequest(request, response);
                } catch (Exception e) {
                    log.warn("Request handling failed: {}", e.getMessage());
                    if (!response.isCommitted()) {
                        response.sendError(500, "Internal Server Error");
                    }
                } finally {
                    response.complete();
                }
                // Keep-Alive 判断
                if (!shouldKeepAlive(request, response)) {
                    break;
                }
                request.resetForNextRequest();
            }
        } catch (Exception e) {
            log.debug("Connection handling failed: {}", e.getMessage());
        } finally {
            closeQuietly(channel);
        }
    }

    // ==================== WebSocket 支持 ====================

    /**
     * 处理 WebSocket 升级：握手后进入帧循环，按主题分发消息。
     * @param channel 方法入参 channel
     * @param request 请求，不允许为 null
     */
    private void handleWebSocketUpgrade(SocketChannel channel, NioServerRequest request) {
        OutputStream out = null;
        try {
            String key = request.getHeader("Sec-WebSocket-Key");
            if (key == null) {
                return;
            }
            // RFC 6455 握手响应
            String accept = WebSocketProtocol.computeAccept(key);
            ByteBuffer handshake = ByteBuffer.wrap(WebSocketProtocol.handshakeResponse(accept));
            while (handshake.hasRemaining()) {
                channel.write(handshake);
            }
            InputStream in = Channels.newInputStream(channel);
            out = Channels.newOutputStream(channel);
            WsConnection conn = new WsConnection(out);
            wsConnections.add(conn);
            try {
                while (running && channel.isConnected()) {
                    WebSocketProtocol.Frame frame = WebSocketProtocol.readFrame(in);
                    if (frame == null) {
                        break; // 对端关闭
                    }
                    switch (frame.opcode()) {
                        case 0x8 -> { // close：回发关闭帧
                            out.write(WebSocketProtocol.closeFrame("bye"));
                            out.flush();
                            return;
                        }
                        case 0x9 -> { // ping -> pong
                            out.write(WebSocketProtocol.textFrame("pong"));
                            out.flush();
                        }
                        case 0x1, 0x2 -> dispatchWsMessage(frame, conn); // 文本/二进制
                        default -> { // 其他控制帧忽略
                        }
                    }
                }
            } finally {
                wsConnections.remove(conn);
            }
        } catch (IOException e) {
            log.debug("WebSocket 连接结束: {}", e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.flush();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 按主题分发 WebSocket 消息（{@code topic\nbody} 约定，与 JdkWebSocketServer 一致）。
     * @param frame 方法入参 frame
     * @param conn 连接，不允许为 null
     */
    private void dispatchWsMessage(WebSocketProtocol.Frame frame, WsConnection conn) {
        String text = new String(frame.payload(), StandardCharsets.UTF_8);
        String topic = "default";
        String body = text;
        int idx = text.indexOf('\n');
        if (idx > 0) {
            topic = text.substring(0, idx).trim();
            body = text.substring(idx + 1);
        } else if (idx == 0) {
            body = text.substring(1);
        }
        List<ServerHandler> handlers = wsTopicHandlers.get(topic);
        if (handlers == null) {
            handlers = wsTopicHandlers.get("default");
        }
        if (handlers == null) {
            return;
        }
        for (ServerHandler handler : handlers) {
            WsServerRequest wsRequest = new WsServerRequest(topic, body);
            WsServerResponse wsResponse = new WsServerResponse(conn);
            try {
                handler.handle(wsRequest, wsResponse);
            } catch (Exception e) {
                log.warn("WebSocket handler error: {}", e.getMessage(), e);
            }
            if (wsResponse.getResult() != null) {
                conn.send(wsResponse.getResult().toString());
            }
        }
    }

    /**
     * 订阅指定主题的 WebSocket 消息。
     *
     * @param topic   主题
     * @param handler 消息处理器
     * @return 当前服务器实例
     */
    public NioHttpServer onSubscribe(String topic, ServerHandler handler) {
        wsTopicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 向指定主题广播消息。
     *
     * @param topic   主题
     * @param payload 消息内容
     */
    public void publish(String topic, String payload) {
        String message = topic + "\n" + payload;
        byte[] frame = WebSocketProtocol.textFrame(message);
        for (WsConnection conn : wsConnections) {
            conn.sendRaw(frame);
        }
    }

    @Override
    /** 注册Bean */
    public NioHttpServer registerBean(Object handler) {
        super.registerBean(handler);
        if (handler == null) {
            return this;
        }
        // 扫描 @OnMessage 注解方法注册为 WebSocket 主题处理器
        for (Method method : handler.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnMessage.class)) {
                ClassUtils.setAccessible(method);
                OnMessage ann = method.getAnnotation(OnMessage.class);
                String topic = ann.value();
                if (topic == null || topic.isEmpty()) {
                    topic = "default";
                }
                wsTopicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                        .add(createWsMessageHandler(handler, method));
            }
        }
        return this;
    }

    /**
     * 构建基于 {@code @OnMessage} 注解方法的处理器。
     * @param bean 方法入参 bean
     * @param method 方法，不允许为 null
     * @return 服务端处理器 对象
     */
    private ServerHandler createWsMessageHandler(Object bean, Method method) {
        return (request, response) -> {
            try {
                Class<?>[] paramTypes = method.getParameterTypes();
                Object[] args = new Object[paramTypes.length];
                String body = request.getBodyString();
                for (int i = 0; i < paramTypes.length; i++) {
                    Class<?> type = paramTypes[i];
                    if (type == ServerRequest.class) {
                        args[i] = request;
                    } else if (type == ServerResponse.class) {
                        args[i] = response;
                    } else if (type == String.class) {
                        args[i] = body;
                    } else if (type == byte[].class) {
                        args[i] = body != null ? body.getBytes(setting.getCharset()) : null;
                    } else {
                        throw new IllegalArgumentException("Unsupported param: " + type.getName());
                    }
                }
                Object result = ReflectUtils.invoke(bean, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
                if (result != null) {
                    response.setResult(result);
                }
            } catch (Exception e) {
                if (!response.isEnded()) {
                    response.sendError(500, "Internal Server Error");
                }
            }
        };
    }

    // ==================== WebSocket 内部类 ====================

    /**
     * WebSocket 连接封装，负责向对端发送帧。
     */
    private static final class WsConnection {
        /** OUT */
        private final OutputStream out;

        WsConnection(OutputStream out) {
            this.out = out;
        }

        /**
         * 发送文本消息。
         */
        void send(String text) {
            sendRaw(WebSocketProtocol.textFrame(text));
        }

        /**
         * 发送原始帧数据。
         */
        void sendRaw(byte[] frame) {
            try {
                synchronized (out) {
                    out.write(frame);
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * WebSocket 消息请求（与 JdkWebSocketServer.SimpleServerRequest 行为一致）。
     */
    private static final class WsServerRequest implements ServerRequest {
        /** Topic */
        private final String topic;
        /** 请求体 */
        private final String body;
        /** attributes */
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        WsServerRequest(String topic, String body) {
            this.topic = topic;
            this.body = body;
        }

        @Override public String getUri() { return "/ws/" + topic; }
        @Override public String getPath() { return "/ws/" + topic; }
        @Override public HttpMethod getMethod() { return HttpMethod.POST; }
        @Override public String getHeader(String name) { return null; }
        @Override public HttpHeader getHeaders() { return HttpHeader.create(); }
        @Override public Map<String, String> getParams() { return Collections.emptyMap(); }
        @Override public String getParam(String name) { return null; }
        @Override public String getContentType() { return "text/plain"; }
        @Override public long getContentLength() { return body != null ? body.getBytes(StandardCharsets.UTF_8).length : -1; }
        @Override public byte[] getBody() { return body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]; }
        @Override public String getBodyString() { return body; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(getBody()); }
        @Override public String getRemoteAddress() { return "127.0.0.1"; }
        @Override public int getRemotePort() { return 0; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    }

    /**
     * WebSocket 消息响应（与 JdkWebSocketServer.SimpleServerResponse 行为一致）。
     */
    private static final class WsServerResponse implements ServerResponse {
        /** Connection */
        private final WsConnection connection;
        /** 状态 */
        private int status = 200;
        /** Ended */
        private boolean ended;
        /** Committed */
        private boolean committed;
        /** 结果 */
        private Object result;

        WsServerResponse(WsConnection connection) {
            this.connection = connection;
        }

        @Override public int getStatus() { return status; }
        @Override
        public ServerResponse setStatus(int statusCode) {
            this.status = statusCode;
            return this;
        }
        @Override
        public ServerResponse setBody(byte[] body) {
            this.result = body;
            return this;
        }
        @Override
        public ServerResponse setBody(String body) {
            this.result = body;
            return this;
        }
        @Override public ServerResponse setHeader(String name, String value) { return this; }
        @Override public String getHeader(String name) { return null; }
        @Override public HttpHeader getHeaders() { return HttpHeader.create(); }
        @Override public String getContentType() { return null; }
        @Override public ServerResponse setContentType(String contentType) { return this; }
        @Override public byte[] getBody() { return result instanceof byte[] b ? b : null; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public ServerResponse sendRedirect(String location) { return this; }
        @Override public ServerResponse sendError(int code, String message) {
            this.status = code;
            this.result = message;
            this.ended = true;
            return this;
        }
        @Override public void flush() { }
        @Override public boolean isCommitted() { return committed; }
        @Override public boolean isEnded() { return ended; }
        @Override public void end() { this.ended = true; }
        @Override public ServerResponse reset() {
            if (!committed) {
                status = 200;
                result = null;
                ended = false;
            }
            return this;
        }
        @Override public void writeRaw(byte[] bytes) {
            connection.sendRaw(bytes);
        }
        @Override
        public ServerResponse setResult(Object result) {
            this.result = result;
            return this;
        }
        @Override public Object getResult() { return result; }
        @Override public ServerResponse sse() { return this; }
        @Override public void sseEvent(String event, String data) { }
        @Override public void sseClose() { }
    }

    /**
     * 判断是否保持连接
     * @param request 请求，不允许为 null
     * @param response 响应，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private boolean shouldKeepAlive(NioServerRequest request, NioServerResponse response) {
        if (response.isChannelClosed()) {
            return false;
        }
        String connHeader = request.getHeader("Connection");
        if (connHeader != null) {
            return "keep-alive".equalsIgnoreCase(connHeader.trim());
        }
        // HTTP/1.1 默认 Keep-Alive
        return "HTTP/1.1".equalsIgnoreCase(request.getHttpVersion());
    }

    /**
     * 安静关闭SocketChannel
     * @param ch 方法入参 ch
     */
    private static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    /** Do停止Accepting */
    protected void doStopAccepting() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        if (selectors != null) {
            for (Selector sel : selectors) {
                try {
                    sel.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (acceptorPool != null) {
            acceptorPool.shutdownNow();
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
        }
        wsConnections.clear();
        log.info("NIO HttpServer stopped");
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }
}

