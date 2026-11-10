// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.nio;

import com.chua.common.support.collection.wrap.Wraps;
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

    /**
     * 服务器通道
     */
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
    /**
     * Pendingacceptqueues
     */
    private java.util.Queue<SocketChannel>[] pendingAcceptQueues;

    /**
     * 待注册的监听通道（摘流后恢复接纳时使用）。
     *
     * <p>恢复接纳需要重新 bind 一个 {@link ServerSocketChannel}。跨线程 {@code register}
     * 与事件循环里的 {@code select()} 是竞态：{@code wakeup()} 只能把线程从 select 唤醒，
     * 不能保证 register 与 select 的先后（连续启停时 OP_ACCEPT 可能完全不触发）。
     * 这里与普通连接的注册采用同一套办法——调用线程只发布通道，由分片 0 的事件循环
     * 线程自己完成 register。</p>
     */
    private volatile ServerSocketChannel pendingAcceptChannel;

    /**
     * 握手队列最小长度（等价于「用平台允许的最大队列」，内核按 somaxconn 截断）。
     */
    private static final int HANDSHAKE_BACKLOG = 65536;

    /**
     * 执行器
     */
    private ExecutorService executor;
    /**
     * Acceptor池
     */
    private ExecutorService acceptorPool;
    /**
     * SSL上下文
     */
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
     * 头体合并写阈值：响应头+体不超过该字节数时合并成单个 ByteBuffer 一次写出。
     * <p>非合并时每次响应要两次 {@code SocketChannel.write}(header/body 各一次),
     * Windows 上每次内核态往返成本明显,合并后每请求只写一次。</p>
     * <p>可通过 {@code -Dnio.combine=<字节数>} 覆盖,设为 {@code 0} 即彻底关闭合并;
     * 该属性在类初始化时读取一次,运行时无额外开销。</p>
     */
    private static final int COMBINE_THRESHOLD = Integer.getInteger("nio.combine", 65536);

    /**
     * 事件循环 select() 超时(毫秒): 可通过 -Dbench.nio.select.timeout 覆盖, 默认 50ms.
     * <p>基准测试中实测事件循环通常几微秒内就有就绪事件, 超时仅用于兜底; 该属性在类
     * 初始化时读取一次, 运行时无额外开销.</p>
     */
    private static final long SELECT_TIMEOUT_MS = Long.parseLong(System.getProperty("bench.nio.select.timeout", "50"));

    /**
     * 单条连接一次 pipeline 最多连续处理的请求数。
     *
     * <p>HTTP/1.1 允许客户端在一条 keep-alive 连接上连续发多条请求，服务端必须按序回应。
     * 但内联路径跑在事件循环线程上，一条连接上无限重放会独占整个分片；到上限就停止重放、
     * 恢复 OP_READ 让出线程，剩余字节仍留在解析缓冲中，下次读事件继续。</p>
     */
    private static final int MAX_PIPELINE_DEPTH = Integer.getInteger("nio.pipeline.max", 4096);

    /**
     * 将响应头与体追加到连接的写队列,小响应合并成单个缓冲以减少 socket 写次数。
     * <p>调用方需持有 {@code st.writeQueue} 的锁。</p>
     * @param st 连接状态
     * @param header 已编码的响应头缓冲
     * @param body 响应体缓冲,可为 null 或已写尽
     */
    private static void appendResponseBuffers(ConnectionState st, ByteBuffer header, ByteBuffer body) {
        if (body != null && body.hasRemaining()
                && header.remaining() + body.remaining() <= COMBINE_THRESHOLD) {
            // 小响应:头体合并为单缓冲,一次 write 完成(对齐 AioHttpServer 的极限优化)
            int total = header.remaining() + body.remaining();
            ByteBuffer combined = st.takeCombineBuffer(total);
            combined.put(header).put(body);
            combined.flip();
            st.writeQueue.add(combined);
            st.pendingWriteBytes += total;
            // 探针: 记录每笔响应的入队(这样 [E] 与第一个 [W] 的差值 = 排队等待 OP_WRITE 的时间)
            WriteProbe.enqueued(total);
            return;
        }
        st.writeQueue.add(header);
        st.pendingWriteBytes += header.remaining();
        WriteProbe.enqueued(header.remaining());
        if (body != null && body.hasRemaining()) {
            st.writeQueue.add(body);
            st.pendingWriteBytes += body.remaining();
        }
    }

    /**
     * 字节数组版本的入队：与 {@link #appendResponseBuffers} 语义一致，但省掉两个
     * {@code ByteBuffer.wrap} 包装对象。
     *
     * <p>{@code NioServerResponse#complete()} 手里本来就是 {@code byte[]}，原先必须
     * 先包成两个 ByteBuffer 才能交给写出方（JFR：包装对象占全部申请的 15%）。</p>
     *
     * <p>调用方需持有 {@code st.writeQueue} 的锁。</p>
     *
     * @param st 连接状态
     * @param header 已编码的响应头
     * @param body 响应体，可为 null
     */
    private static void appendResponseBuffers(ConnectionState st, byte[] header, byte[] body) {
        int bodyLength = body == null ? 0 : body.length;
        int total = header.length + bodyLength;
        if (total <= COMBINE_THRESHOLD) {
            ByteBuffer combined = st.takeCombineBuffer(total);
            combined.put(header);
            if (bodyLength > 0) {
                combined.put(body);
            }
            combined.flip();
            st.writeQueue.add(combined);
            st.pendingWriteBytes += total;
            WriteProbe.enqueued(total);
            return;
        }
        // 超大响应不做合并：直接包装数组（此处每条响应才两个包装对象，且是低频路径）
        st.writeQueue.add(ByteBuffer.wrap(header));
        st.pendingWriteBytes += header.length;
        WriteProbe.enqueued(header.length);
        if (bodyLength > 0) {
            st.writeQueue.add(ByteBuffer.wrap(body));
            st.pendingWriteBytes += bodyLength;
        }
    }

    /**
     * 内联路径（事件循环线程同步写）的写出回调，按连接复用一个实例。
     *
     * <p>同时实现三个接口：{@code BiConsumer<ByteBuffer,ByteBuffer>} 是
     * {@code NioServerResponse#setAsyncWriter} 的通用契约，
     * {@code Consumer<byte[]>} 是流式直写契约，
     * {@link NioServerResponse.ByteArrayAsyncWriter} 让 {@code complete()} 能把
     * {@code byte[]} 直接交过来，不必先包成 ByteBuffer。</p>
     */
    private final class InlineWriter implements java.util.function.BiConsumer<ByteBuffer, ByteBuffer>,
            java.util.function.Consumer<byte[]>, NioServerResponse.ByteArrayAsyncWriter {

        private final ConnectionState st;
        private final SelectionKey key;

        InlineWriter(ConnectionState st, SelectionKey key) {
            this.st = st;
            this.key = key;
        }

        @Override
        public void accept(ByteBuffer header, ByteBuffer body) {
            synchronized (st.writeQueue) {
                appendResponseBuffers(st, header, body);
            }
            flushInlineWrite(st, key);
        }

        @Override
        public void write(byte[] header, byte[] body) {
            synchronized (st.writeQueue) {
                appendResponseBuffers(st, header, body);
            }
            flushInlineWrite(st, key);
        }

        @Override
        public void accept(byte[] bytes) {
            enqueueStream(st, key, bytes);
            flushInlineWrite(st, key);
        }
    }

    /**
     * worker（虚拟线程直写）路径的写出回调，同样按连接复用一个实例。
     *
     * <p>与 {@link InlineWriter} 的区别：响应字节只入队，由
     * {@link #processRequestDirectWrite} 统一排空；流式分片则在本回调里立即排空，
     * 保持原有 SSE 背压语义。</p>
     */
    private final class DirectWriter implements java.util.function.BiConsumer<ByteBuffer, ByteBuffer>,
            java.util.function.Consumer<byte[]>, NioServerResponse.ByteArrayAsyncWriter {

        private final ConnectionState st;
        private final SelectionKey key;

        DirectWriter(ConnectionState st, SelectionKey key) {
            this.st = st;
            this.key = key;
        }

        @Override
        public void accept(ByteBuffer header, ByteBuffer body) {
            synchronized (st.writeQueue) {
                appendResponseBuffers(st, header, body);
            }
        }

        @Override
        public void write(byte[] header, byte[] body) {
            synchronized (st.writeQueue) {
                appendResponseBuffers(st, header, body);
            }
        }

        @Override
        public void accept(byte[] bytes) {
            enqueueStream(st, key, bytes);
            synchronized (st.writeQueue) {
                drainWriteQueue(st, key);
            }
        }
    }

    /**
     * 流式分片入队(SSE/{@code writeRaw}):受单连接背压门限约束。
     *
     * <p>与 {@link #appendResponseBuffers} 的区别:这里是**生产端持续推送**的路径,
     * 慢客户端会让队列无界增长,所以必须在入队前检查
     * {@link ServerSetting#getMaxPendingWriteBytes()};超限视为慢消费者,
     * 关闭连接并丢弃该分片(整帧语义,不会写出半帧)。</p>
     *
     * @param st    连接状态
     * @param key   选择键
     * @param bytes 分片字节
     */
    private void enqueueStream(ConnectionState st, SelectionKey key, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        long max = setting.getMaxPendingWriteBytes();
        synchronized (st.writeQueue) {
            if (max > 0 && st.pendingWriteBytes + bytes.length > max) {
                // 慢客户端:队列已到门限。这里不阻塞等待——SSE 生产者可能正是事件循环线程,
                // 阻塞它会连同该分片上的所有连接一起卡死。关闭连接是唯一安全的选择:
                // 响应体已无法在有限内存内完整送达。
                log.warn("NIO 待写队列超过背压门限 (pending={}, max={}, remote={}),关闭连接",
                        st.pendingWriteBytes, max, safeRemote(st.channel));
                st.keepAlive = false;
                st.inWorker = false;
                closeConn(key, st);
                return;
            }
            st.writeQueue.add(ByteBuffer.wrap(bytes));
            st.pendingWriteBytes += bytes.length;
        }
    }

    /**
     * 创建 NioHttpServer 实例
     * @param setting setting
     */
    public NioHttpServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * Do开始
     */
    @Override
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
            // 高并发连接接纳：backlog 用平台允许的最大队列。内核会按 somaxconn 截断，
            // 配得大不会预分配内存，也不会因此拒绝「突发建连」——真正的硬上限在系统参数里
            int backlog = setting.resolveBacklog(HANDSHAKE_BACKLOG);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), backlog);

            // 回填实际端口（port=0 时由系统分配）
            InetSocketAddress bound = (InetSocketAddress) serverChannel.getLocalAddress();
            setting.setPort(bound.getPort());

// [P3C C1 豁免] 场景化创建线程，需文档化
            executor = Executors.newVirtualThreadPerTaskExecutor();
            // 多 Selector 分片:每分片一个事件循环线程,连接按 hash 分散注册,
            // 解决单事件循环在高并发(2000+)下成为吞吐瓶颈的问题(类 Netty 主从模型)。
            // OP_ACCEPT 由事件循环线程 0 自行注册(eventLoop 内与 select 同线程,见下),
            // 新连接入队后由目标分片线程自行 register(与 select 同线程),消除跨线程注册竞态。
            // 分片数上限 2 为实测稳定值:Windows 下 Selector 数 >2 时(4/8 分片实测)
            // 出现请求超时/连接被拒(WindowsSelectorImpl 多 Selector 并发稳定性限制),
            // 与"百万级 RPS"目标冲突但无法在本平台规避,保持 2 分片确保零失败
            int eventLoops = setting.getEventLoops();
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            // 分片数统一由 ServerSetting#resolveEventLoops 决定：平台差异（Windows 上
            // WindowsSelectorImpl 多 Selector 并发 select 会丢读事件，故 4 为硬上限）
            // 只在一处收敛，避免各实现各写一套 clamp、结论互相矛盾。
            eventLoops = setting.resolveEventLoops(Runtime.getRuntime().availableProcessors(), windows);
            if ("true".equals(System.getProperty("bench.fast"))) {
                // 基准测试:bench.el 系统属性可强制 event loops 数量(绕过 Windows clamp),
                // 用于验证不同分片数下的吞吐拐点
                String el = System.getProperty("bench.el");
                if (el != null) {
                    try {
                        eventLoops = Math.max(1, Integer.parseInt(el));
                    } catch (NumberFormatException e) {
                        log.debug("[nio] 解析 bench.el 失败，沿用 {}: {}", eventLoops, e.getMessage());
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
        if (idx == 0) {
            registerAcceptChannel(sel);
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
                // 探针: 事件循环进入 select 前
                WriteProbe.selEnter();
                sel.select(SELECT_TIMEOUT_MS);
                // 探针: 事件循环离开 select 返回
                WriteProbe.selExit();
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
                    if (!ac.isOpen()) {
                        releaseConnection();
                        continue;
                    }
                    try {
                        ConnectionState st = new ConnectionState(ac, setting.getMaxRequestSize(), setting.getCharset());
                        st.shard = idx;
                        ac.register(sel, SelectionKey.OP_READ, st);
                    } catch (Exception e) {
                        closeQuietly(ac);
                        releaseConnection();
                    }
                }
                // 摘流后恢复接纳：新监听通道同样由本线程注册（见 pendingAcceptChannel 注释）
                if (idx == 0 && pendingAcceptChannel != null) {
                    registerAcceptChannel(sel);
                }
                // 探针: 每轮事件循环选中的 key 数量 (KS)
                WriteProbe.keysSelected(sel.selectedKeys().size());
                // 连接风暴优先：先排空 acceptable key，避免它排在大量 read key 之后，
                // 让内核 accept 队列在这几十毫秒里溢出（Windows 满队列直接回 RST）。
                // 只处理 OP_ACCEPT，其余 key 仍交给下方统一遍历；异常只记录，不杀事件循环。
                if (idx == 0) {
                    java.util.Iterator<SelectionKey> acceptIt = sel.selectedKeys().iterator();
                    while (acceptIt.hasNext()) {
                        SelectionKey ak = acceptIt.next();
                        if (!ak.isValid() || !ak.isAcceptable()) {
                            continue;
                        }
                        acceptIt.remove();
                        try {
                            handleAccept(ak);
                        } catch (Throwable t) {
                            log.warn("Event loop[{}] accept error: {} -> {}", idx,
                                    t.getClass().getSimpleName(), t.getMessage(), t);
                        }
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
                                t.getClass().getSimpleName(), t.getMessage(), t);
                        try {
                            key.cancel();
                        } catch (Exception e) {
                            log.debug("[nio] 取消 SelectionKey 失败，已忽略: {}", e.getMessage());
                        }
                        ConnectionState st = (ConnectionState) key.attachment();
                        closeQuietly(st != null ? st.channel : null);
                    }
                }
            } catch (Throwable t) {
                // 事件循环线程绝不能死:任何未预期异常(含 Runtime/Error)都只记录并继续,
                // 否则该分片连接的 accept/read/write 全部停摆(高并发压力下实测请求 100% 超时)
                if (running) {
                    log.warn("Event loop[{}] error: {} -> {}", idx, t.getClass().getSimpleName(), t.getMessage(), t);
                }
            }
        }
    }

    /**
     * 处理Accept。
     *
     * <p>一次就绪批量 drain（上限 {@link ServerSetting#getAcceptBatch()}）：突发建连时
     * accept 队列会瞬间积压几百上千个已完成握手的连接，一次只 accept 一个会让队尾排到客户端超时。
     * 批量必须有上限，否则单分片在 accept 上停留过久会拖慢已建立连接的读写。</p>
     *
     * <p>每个连接先过连接预算（{@link #tryAcquireConnection()}）：CAS 判上限 + 计数，
     * 超限直接回 503 并关闭，绝不把「已经超卖」的连接注册进 Selector —— 那才是百万连接
     * 场景下真正 OOM 的来源。</p>
     *
     * @param key 键，不允许为 null
     * @throws IOException 异常
     */
    private void handleAccept(SelectionKey key) throws IOException {
        // 连接风暴时一轮排空内核 accept 队列：Windows 把 backlog 静默截断到约 200，队列一满
        // 就回 RST（这些连接根本到不了 tryAcquireConnection）。固定只 accept batch 条就返回
        // 会让剩余连接在后续 select 周期里持续溢出，因此 drain 到 accept()==null 为止。
        // acceptBatch 退化为每轮安全阀，避免 SYN 洪泛独占事件循环分片。
        int budget = Math.max(setting.getAcceptBatch(), 1);
        long wakeMask = 0L;
        int drained = 0;
        while (drained < budget) {
            SocketChannel accepted = serverChannel.accept();
            if (accepted == null) {
                break;
            }
            drained++;
            if (!accepting) {
                // 优雅关闭中：不再接纳，直接关闭（存量连接不受影响）
                closeQuietly(accepted);
                continue;
            }
            if (!tryAcquireConnection()) {
                // 超限：尽量回一个明确的 503 让上游摘流，然后关闭
                writeOverloadAndClose(accepted);
                continue;
            }
            // 缓冲区按配置应用（默认 0 = 不动内核默认值，无额外系统调用）
            applyChannelSocketOptions(accepted);
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
                        log.warn("TLS 握手失败，关闭连接: {}", e.getMessage(), e);
                        closeQuietly(accepted);
                        releaseConnection();
                    }
                });
                continue;
            }
            // 非阻塞注册:连接按 hash 分散到各分片,由目标分片事件循环线程自行 register
            accepted.configureBlocking(false);
            accepted.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
            int shard = (accepted.hashCode() & Integer.MAX_VALUE) % selectors.length;
            // 只入队:register 动作交由目标分片事件循环线程执行(与 select 同线程),
            // 避免跨线程 register 与 select() 的竞态导致 OP_READ 感知不到、请求超时
            pendingAcceptQueues[shard].add(accepted);
            wakeMask |= 1L << shard;
        }
        // 按分片聚合 wakeup：一轮上千次 wakeup 会让 selector 自旋空转
        for (int i = 0; i < selectors.length; i++) {
            if ((wakeMask & (1L << i)) != 0) {
                selectors[i].wakeup();
            }
        }
    }

    /**
     * 对超出连接上限的客户端回 503 后关闭。
     *
     * <p>只做一次尽力而为的短写：此时连请求都还没读，写失败也无所谓，关键是别让连接
     * 挂在那里等超时（那会白白占住一个 fd）。</p>
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
                channel.configureBlocking(false);
                channel.write(new ByteBuffer[]{resp, ByteBuffer.wrap(body)});
            }
        } catch (Exception e) {
            log.debug("[nio] 写 503 拒绝响应失败，直接关闭: {}", e.getMessage());
        } finally {
            closeQuietly(channel);
        }
    }

    /**
     * 处理读取
     * @param key 键，不允许为 null
     * @return 返回值
     * @throws IOException 异常
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
        // 探针: 数据到达服务端 (read() 返回, 落用户态缓冲)
        WriteProbe.received(n);
        buf.flip();
        // 探针: 请求开始解析 (feed() 之前)
        WriteProbe.entered(n);
        int r = st.request.feed(buf);
        buf.compact(); // 保留未消费数据
        if (r == 1) {
            // 完整请求解析完成:摘除 OP_READ
            key.interestOps(0);
            st.inWorker = true;
            // 内联快速路径按需启用(setting.isInlineDispatch=true 且非 SSL/WS):
            // 非阻塞 handler 事件循环线程内执行并同步写出,省虚拟线程提交 + Selector 唤醒
            // 一次 read 里已缓存多条请求时改走 worker：pipeline 循环可能很长，
            // 不能让事件循环线程被单条连接独占
            boolean pipelined = st.request.hasBufferedInput()
                    || (st.readBuf != null && st.readBuf.position() > 0);
            if (setting.isInlineDispatch() && sslContext == null && !pipelined
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
            int depth = 0;
            boolean hasPending = false;
            // 循环期间始终摘除 OP_READ、保持 inWorker=true：一条连接上的 pipeline 请求
            // 必须严格串行处理，响应按 FIFO 入队，绝不并发调用 handler 或写 channel。
            while (true) {
                // Upgrade 需要切阻塞模式跑帧协议，不能在内联事件循环线程上执行
                if (WebSocketProtocol.isUpgradeRequest(st.request)) {
                    closeConn(key, st);
                    return;
                }
                NioServerResponse response = st.response();
                // 回调按连接缓存:捕获项只有 st 与 key(连接内不变),每请求重建纯属浪费。
                // 同一个实例同时实现字节数组直交接口,避免 complete() 为每条响应新建两个 ByteBuffer 包装。
                InlineWriter writer = st.inlineWriter;
                if (writer == null) {
                    writer = new InlineWriter(st, key);
                    st.inlineWriter = writer;
                }
                response.setAsyncWriter(writer);
                // 流式直写(SSE):入待写队列并受背压门限约束
                response.setStreamWriter(writer);
                // 响应头必须与实际生命周期一致：要关的连接不能对外宣称 keep-alive
                response.setConnectionClose(!shouldKeepAlive(st.request, response));
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
                // 响应字节已交付写队列，此刻归位实例供下一条请求复用
                recycle(st, response);
                depth++;
                synchronized (st.writeQueue) {
                    hasPending = !st.writeQueue.isEmpty();
                }
                // 背压未写完、连接要关时停止重放；超长 pipeline 直接关闭，
                // 避免残留请求留在用户态缓冲里却只恢复 OP_READ
                if (depth >= MAX_PIPELINE_DEPTH) {
                    closeConn(key, st);
                    return;
                }
                if (!st.keepAlive || !running || st.closed.get() || hasPending) {
                    break;
                }
                int r = replayNextRequest(st);
                if (r < 0) {
                    closeConn(key, st);
                    return;
                }
                if (r == 0) {
                    break;
                }
                // r == 1：解析器已解出下一条请求，继续本连接串行处理
            }
            // 统一收尾：整个循环只入队一次，避免 rearm 在 pipeline 期间提前恢复 OP_READ
            if (hasPending) {
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            } else {
                finishAfterWrite(st, key);
            }
        } catch (Exception e) {
            log.warn("Inline worker failed: {}", e.getMessage(), e);
            closeConn(key, st);
        }
    }

    /**
     * 重放解析缓冲里已缓存的下一条 pipeline 请求；若单次 read 超过解析器 8KB 行缓冲，
     * 剩余字节还留在连接读缓冲中，需要一并续上。
     *
     * <p>调用前必须已 {@code resetForNextRequest()}，否则 COMPLETE 状态会重复处理上一条。</p>
     *
     * @param st 连接状态
     * @return 1=解出下一条完整请求；0=需要真正读 socket；-1=解析错误
     */
    private int replayNextRequest(ConnectionState st) {
        int r = st.request.feedBuffered();
        if (r == 0 && st.readBuf != null && st.readBuf.position() > 0) {
            // readBuf 处于 write 模式（handleRead 的 compact 之后），flip 后可读
            st.readBuf.flip();
            r = st.request.feed(st.readBuf);
            st.readBuf.compact();
        }
        return r;
    }

    /**
     * 把响应对象归位以复用于下一条请求。
     *
     * <p>SSE 响应的生命周期会延续到 handler 返回之后（由 {@code sseClose} 收尾），
     * 此时重置会破坏仍在推送的流，因此直接丢弃缓存，让下一条请求取新实例。</p>
     *
     * @param st       连接状态
     * @param response 刚处理完的响应
     */
    private static void recycle(ConnectionState st, NioServerResponse response) {
        if (!response.resetForNextRequest()) {
            st.response = null;
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
                    // 探针: 实际 socket write 时刻
                    WriteProbe.written(w);
                    if (w < 0) {
                        closeConn(key, st);
                        return;
                    }
                    st.pendingWriteBytes -= w;
                    if (bb.hasRemaining()) {
                        return;
                    }
                } catch (IOException e) {
                    closeConn(key, st);
                    return;
                }
                st.writeQueue.poll();
                st.recycleCombineBuffer(bb);
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
            NioServerResponse response = st.response();
            response.setAsyncWriter((header, body) -> {
                synchronized (st.writeQueue) {
                    appendResponseBuffers(st, header, body);
                }
                // 交由所属分片事件循环线程统一注册 OP_WRITE,避免 worker 线程跨线程改 interestOps 竞态
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            });
            // 流式直写(SSE):分片入待写队列并由事件循环续写;受背压门限约束
            response.setStreamWriter(bytes -> {
                enqueueStream(st, key, bytes);
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            });
            try {
                handleRequest(st.request, response);
            } catch (Exception e) {
                log.warn("Request handling failed: {}", e.getMessage(), e);
                if (!response.isCommitted()) {
                    response.sendError(500, "Internal Server Error");
                }
            } finally {
                response.complete();
            }
            st.keepAlive = shouldKeepAlive(st.request, response);
            st.request.resetForNextRequest();
            recycle(st, response);
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
            log.warn("Worker handling failed: {} -> {}", e.getClass().getSimpleName(), e.getMessage(), e);
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
            int depth = 0;
            boolean allWritten = true;
            // 与内联路径同一套 pipeline 语义：一条连接只有一个 worker 串行处理，
            // 响应按 FIFO 入队并同步排空，保证 HTTP/1.1 要求的响应顺序。
            while (true) {
                // WebSocket 升级:回退到虚拟线程帧协议处理
                if (WebSocketProtocol.isUpgradeRequest(st.request)) {
                    key.cancel();
                    st.channel.configureBlocking(true);
                    handleWebSocketUpgrade(st.channel, st.request);
                    closeConn(key, st);
                    return;
                }
                NioServerResponse response = st.response();
                // 写出回调按连接缓存，避免每条请求新建 lambda / ByteBuffer 包装
                DirectWriter writer = st.directWriter;
                if (writer == null) {
                    writer = new DirectWriter(st, key);
                    st.directWriter = writer;
                }
                response.setAsyncWriter(writer);
                // 流式直写(SSE):worker 线程排空队列;排不完交由事件循环 OP_WRITE 续写
                response.setStreamWriter(writer);
                // 响应头必须与实际生命周期一致：要关的连接不能对外宣称 keep-alive
                response.setConnectionClose(!shouldKeepAlive(st.request, response));
                try {
                    handleRequest(st.request, response);
                } catch (Exception e) {
                    log.warn("Request handling failed: {}", e.getMessage(), e);
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
                boolean written;
                synchronized (st.writeQueue) {
                    written = drainWriteQueue(st, key);
                }
                if (!written) {
                    // 背压：剩余 pipeline 请求字节在用户态缓冲里，退出循环后 socket 不会再有
                    // 读事件，会永久挂死。worker 是虚拟线程，在此短暂同步等待并重试排空，
                    // 全程单连接严格串行，不提交并发任务（否则响应会串位）。
                    for (int k = 0; k < 500 && !written; k++) {
                        java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
                        synchronized (st.writeQueue) {
                            written = drainWriteQueue(st, key);
                        }
                    }
                    if (!written) {
                        closeConn(key, st);
                        return;
                    }
                }
                // 响应字节已入队(队列持有各自的 byte[]),此刻归位实例供下一条请求复用
                recycle(st, response);
                depth++;
                allWritten = true;
                if (depth >= MAX_PIPELINE_DEPTH) {
                    // 超长 pipeline 视为滥用，明确关闭而不是静默挂住
                    closeConn(key, st);
                    return;
                }
                if (!st.keepAlive || !running || st.closed.get()) {
                    break;
                }
                int r = replayNextRequest(st);
                if (r < 0) {
                    closeConn(key, st);
                    return;
                }
                if (r == 0) {
                    break;
                }
            }
            // 整个循环只入队一次，避免 rearm 在 pipeline 期间提前恢复 OP_READ
            if (allWritten) {
                // 直写完成:通知事件循环恢复 OP_READ(interestOps 仍由事件循环线程修改)
                rearmReadQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            } else {
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            }
        } catch (Exception e) {
            log.warn("DirectWrite worker failed: {} -> {}", e.getClass().getSimpleName(), e.getMessage(), e);
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
                // 探针: 实际 socket write 时刻
                WriteProbe.written(w);
                if (w < 0) {
                    closeConn(key, st);
                    return true;
                }
                st.pendingWriteBytes -= w;
                if (bb.hasRemaining()) {
                    return false;
                }
            } catch (IOException e) {
                closeConn(key, st);
                return true;
            }
            st.writeQueue.poll();
            st.recycleCombineBuffer(bb);
        }
    }

    /**
     * 处理写入
     * @param key 键，不允许为 null
     * @return 返回值
     * @throws IOException 异常
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
                // 探针: 实际 socket write 时刻
                WriteProbe.written(w);
                if (w < 0) {
                    closeConn(key, st);
                    return;
                }
                st.pendingWriteBytes -= w;
                if (bb.hasRemaining()) {
                    // 未写完,等待下次 OP_WRITE
                    return;
                }
                st.writeQueue.poll();
                st.recycleCombineBuffer(bb);
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
        if (!st.closed.compareAndSet(false, true)) {
            return;
        }
        try {
            key.cancel();
        } catch (Exception e) {
            log.debug("[nio] 操作失败，已忽略: {}", e.getMessage());
        }
        closeQuietly(st.channel);
        // 连接预算必须与 tryAcquireConnection() 严格配对，且只释放一次：
        // 一条连接可能被「读结束 / 写结束 / 异常 / stop()」多条路径关闭，用标志位保证幂等。
        if (st.budgetAcquired) {
            st.budgetAcquired = false;
            releaseConnection();
        }
    }

    /**
     * 连接状态:非阻塞通道 + 增量解析器 + 读缓冲 + 待写队列
     */
    private static final class ConnectionState {
        final SocketChannel channel;
        final NioServerRequest request;
        // 每连接读缓冲:首次收到数据时懒分配(空闲连接 0 占用,百万级空闲连接省数十 GB),
        // 连接生命周期内复用同一缓冲,无跨连接共享风险;
        // 32KB 减少大请求体场景下的 read 系统调用次数,小请求场景无额外开销(仅按需 flip)
        ByteBuffer readBuf;
        // 实测 ArrayDeque + synchronized 在 1000/2000 并发下吞吐最高(3876/2430 RPS),
        // 无锁队列 + pendingWrites 因多一轮 select 循环反而降低吞吐
        final java.util.Deque<ByteBuffer> writeQueue = Wraps.deque();
        /**
         * 待写队列中尚未写出的字节数(受 {@link #writeQueue} 监视器保护)。
         *
         * <p>用于实施 {@link ServerSetting#getMaxPendingWriteBytes()} 背压门限:
         * 慢客户端不读响应时,流式(SSE)生产者会持续入队,该计数随之增长;
         * 超过门限即判定为慢消费者并关闭连接,避免堆内存无界膨胀。</p>
         */
        long pendingWriteBytes;
        boolean keepAlive = true;
        boolean inWorker = false;
        /**
         * 连接关闭标志(幂等关闭):closeConn 可能被事件循环、worker 与背压路径并发调用,
         * 若不判重会重复递减 activeConnections,导致连接数上限被提前耗尽。
         */
        final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        /**
         * 是否已占用连接预算（保证 {@code releaseConnection()} 幂等）
         */
        boolean budgetAcquired = true;
        /**
         * 所属分片索引(决定注册到哪个 Selector 与写队列)
         */
        int shard = 0;

        /**
         * 每连接复用的响应对象。
         *
         * <p>keep-alive 下同一连接会连续处理成千上万条请求，原先每条请求都新建一个
         * {@link NioServerResponse}（实测占全部申请的 5.4%）。响应对象只承载「一条响应
         * 的可变状态」，处理完即由 {@link NioServerResponse#resetForNextRequest()} 归位，
         * 所以按连接持有是安全且零共享的。</p>
         *
         * <p>懒创建：只处理过一次请求的连接才分配。</p>
         */
        NioServerResponse response;

        /**
         * 内联路径的写出回调（连接级复用）。
         *
         * <p>原先每条请求都新建两个 lambda（实测合计占全部申请的 5.3%）。它们的捕获项
         * 只有本连接状态与所属 SelectionKey，两者在连接生命周期内都不变，因此可以
         * 在首次使用时创建一次。同一个实例还实现了字节数组直交接口，供
         * {@code NioServerResponse#complete()} 跳过 ByteBuffer 包装。</p>
         */
        InlineWriter inlineWriter;

        /**
         * worker 直写路径的写出回调（连接级复用），语义见 {@link DirectWriter}。
         */
        DirectWriter directWriter;

        /**
         * 头体合并缓冲（连接级复用）。
         *
         * <p>echo 类小响应每请求都要一个「头+体」合并缓冲，原先每次 {@code ByteBuffer.allocate}
         * 新建（实测占全部申请的 6.8%）。这里按连接保留一块，用完后归还。</p>
         *
         * <p><b>安全前提</b>：入队的 {@code ByteBuffer} 在 {@code flip()} 之后只被
         * {@code channel.write} 读取，不会再写入；只有该缓冲彻底出队（写尽）后才会归还，
         * 因此同一块内存不会被两条待写响应同时使用。归还点见
         * {@code flushInlineWrite}/{@code drainWriteQueue} 的 poll 分支。</p>
         */
        ByteBuffer combineBuffer;

        /**
         * 当前已借出、仍在待写队列中的合并缓冲（归还判定用）。
         */
        ByteBuffer borrowedCombine;

        /**
         * Connection State
         * @param channel 参数
         * @param charset 字符串
         * @param maxRequestSize 长整数
         * @return 返回值
        */
        ConnectionState(SocketChannel channel, long maxRequestSize, String charset) {
            this.channel = channel;
            this.request = new NioServerRequest(channel, maxRequestSize, charset);
        }

        /**
         * 取得本连接复用的响应对象（首次调用时创建）。
         *
         * @return 已归位的响应对象
         */
        NioServerResponse response() {
            NioServerResponse r = response;
            if (r == null) {
                r = new NioServerResponse(channel);
                response = r;
            }
            return r;
        }

        /**
         * 取得一块可容纳 {@code total} 字节的合并缓冲（优先复用连接上的那块）。
         *
         * <p>复用的前提是这块缓冲此刻不在待写队列里——归还点保证它写尽之后才回到
         * {@link #combineBuffer}，因此这里拿到的一定是空闲缓冲。容量不足时按需扩容，
         * 旧缓冲交由 GC。</p>
         *
         * @param total 需要的字节数
         * @return 已 {@code clear()} 的缓冲
         */
        ByteBuffer takeCombineBuffer(int total) {
            ByteBuffer buf = combineBuffer;
            if (buf == null || buf.capacity() < total) {
                buf = ByteBuffer.allocate(total);
            } else {
                // 从空闲位取出：置空表示「已借出」，避免下一条响应拿到仍在队列里的同一块
                combineBuffer = null;
            }
            borrowedCombine = buf;
            buf.clear();
            return buf;
        }

        /**
         * 出队的缓冲若是刚借出的那块合并缓冲，就归还到空闲位以便下一条响应复用。
         *
         * <p>用引用相等判定：只有 {@link #takeCombineBuffer} 返回的实例才会命中，
         * 响应头/体各自的 {@code ByteBuffer}（由 {@code wrap} 产生）不会误归还。</p>
         *
         * @param drained 刚从待写队列出队的缓冲
         */
        void recycleCombineBuffer(ByteBuffer drained) {
            if (drained == borrowedCombine) {
                combineBuffer = borrowedCombine;
                borrowedCombine = null;
            }
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
            // 每条连接复用一个响应对象(阻塞路径串行处理,复用无并发风险)
            NioServerResponse response = new NioServerResponse(channel);
            // 懒分配:首次读到数据才分配 16KB,避免空闲连接预占内存
            ByteBuffer readBuf = null;
            // 一次阻塞 read 可能把多条 pipeline 请求一起读进解析缓冲：先重放完缓存的
            // 完整请求再读 socket，否则剩余请求会因无新数据到达而永久挂死。
            boolean replayPending = false;
            while (running && channel.isConnected()) {
                int r;
                if (replayPending) {
                    r = request.feedBuffered();
                    if (r == 0 && readBuf != null && readBuf.position() > 0) {
                        readBuf.flip();
                        r = request.feed(readBuf);
                        readBuf.compact();
                    }
                    // 只有解出完整请求才继续重放；半包必须回到阻塞读等新数据
                    replayPending = (r == 1);
                } else {
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
                    r = request.feed(readBuf);
                    readBuf.compact();
                }
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
                try {
                    handleRequest(request, response);
                } catch (Exception e) {
                    log.warn("Request handling failed: {}", e.getMessage(), e);
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
                // 下一条可能已在同一批字节里，先走重放分支
                replayPending = true;
                if (!response.resetForNextRequest()) {
                    // SSE 等长生命周期响应：不复用，下次循环取新实例
                    response = new NioServerResponse(channel);
                }
            }
        } catch (Exception e) {
            log.debug("Connection handling failed: {}", e.getMessage());
        } finally {
            closeQuietly(channel);
            // SSL 回退路径的连接同样占用了连接预算，必须归还（closeConn 不经过这里）。
            // 与 handleAccept 中的握手失败分支互斥：后者只在 handleConnection 尚未进入时触发。
            releaseConnection();
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
                } catch (IOException e) {
                    log.debug("[nio] 关闭连接失败，已忽略: {}", e.getMessage());
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

    /**
     * 注册Bean
     */
    @Override
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
                Object result = ReflectUtils.invoke(bean, method.getName(), method.getReturnType(),
                        method.getParameterTypes(), args);
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
        /**
         * OUT
         */
        private final OutputStream out;

        /**
         * Ws Connection
         * @param out 输出流
         * @return 返回值
        */
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
            } catch (IOException e) {
                log.debug("[nio] IO 操作失败，已忽略: {}", e.getMessage());
            }
        }
    }

    /**
     * WebSocket 消息请求（与 JdkWebSocketServer.SimpleServerRequest 行为一致）。
     */
    private static final class WsServerRequest implements ServerRequest {
        /**
         * Topic
         */
        private final String topic;
        /**
         * 请求体
         */
        private final String body;
        /**
         * attributes
         */
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        /**
         * Ws Server Request
         * @param body 字符串
         * @param topic 字符串
         * @return 返回值
        */
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
        @Override public long getContentLength() { return body != null ? body.getBytes(StandardCharsets.UTF_8)
                .length : -1; }
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
        /**
         * Connection
         */
        private final WsConnection connection;
        /**
         * 状态
         */
        private int status = 200;
        /**
         * Ended
         */
        private boolean ended;
        /**
         * Committed
         */
        private boolean committed;
        /**
         * 结果
         */
        private Object result;

        /**
         * Ws Server Response
         * @param connection 参数
         * @return 返回值
        */
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
        boolean closed = response.isChannelClosed();
        String connHeader = request.getHeader("Connection");
        if (closed) {
            return false;
        }
        if (connHeader != null) {
            // Connection 允许多 token（如 "keep-alive, Upgrade"），按逗号切分判断；
            // 整串 equalsIgnoreCase 会把这类合法头误判为不可复用。
            boolean keepAlive = false;
            boolean close = false;
            boolean other = false;
            for (String token : connHeader.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if ("keep-alive".equalsIgnoreCase(t)) {
                    keepAlive = true;
                } else if ("close".equalsIgnoreCase(t)) {
                    close = true;
                } else {
                    // Upgrade 等语义不允许按 HTTP/1.1 默认推断为可复用
                    other = true;
                }
            }
            if (close) {
                return false;
            }
            if (keepAlive) {
                return true;
            }
            return !other && "HTTP/1.1".equalsIgnoreCase(request.getHttpVersion());
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
        } catch (IOException e) {
            log.debug("[nio] 关闭服务失败，已忽略: {}", e.getMessage());
        }
    }

    /**
     * Do停止Accepting
     */
    @Override
    protected void doStopAccepting() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                log.debug("[nio] IO 操作失败，已忽略: {}", e.getMessage());
            }
        }
    }

    /**
     * 在分片 0 的事件循环线程内注册 OP_ACCEPT。
     *
     * <p>必须与 {@code select()} 同线程调用：跨线程 {@code register} 与 {@code select()}
     * 并发时，JDK 不保证 select 能看到新注册的键，表现为「连接建立成功但永不 accept」。
     * 因此调用方只能发布 {@link #pendingAcceptChannel}，真正的注册动作统一收敛到这里。</p>
     *
     * @param sel 分片 0 的选择器
     */
    private void registerAcceptChannel(Selector sel) {
        ServerSocketChannel channel = pendingAcceptChannel;
        if (channel == null) {
            channel = serverChannel;
        }
        pendingAcceptChannel = null;
        // 不检查 running:start() 模板在 doStart 返回后才置位，此时（事件循环刚启动）
        // running 仍为 false，检查会让首个 OP_ACCEPT 永不注册，服务器不接受任何连接
        if (channel == null || !channel.isOpen()) {
            return;
        }
        try {
            channel.register(sel, SelectionKey.OP_ACCEPT);
        } catch (Exception e) {
            log.warn("nio 注册 OP_ACCEPT 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 恢复接纳。
     *
     * <p>{@link #doStopAccepting()} 关闭了监听通道（摘流），要恢复必须重新 bind 同一端口。
     * 重新 bind 出的通道不能在本线程直接 register（与事件循环的 select 跨线程竞态），
     * 只发布到 {@link #pendingAcceptChannel} 并唤醒分片 0，由事件循环线程完成注册。</p>
     */
    @Override
    protected void doStartAccepting() {
        if (serverChannel != null && serverChannel.isOpen()) {
            return;
        }
        Selector[] sels = selectors;
        if (sels == null || sels.length == 0 || sels[0] == null) {
            return;
        }
        try {
            ServerSocketChannel channel = ServerSocketChannel.open();
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            channel.setOption(StandardSocketOptions.SO_RCVBUF, Math.max(setting.getBufferSize(), 16384));
            channel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    setting.resolveBacklog(HANDSHAKE_BACKLOG));
            this.serverChannel = channel;
            this.pendingAcceptChannel = channel;
            sels[0].wakeup();
            log.info("NIO HttpServer 已恢复接纳：{}:{}", setting.getHost(), setting.getPort());
        } catch (IOException e) {
            log.warn("NIO HttpServer 恢复接纳失败: {}", e.getMessage(), e);
        }
    }

    /**
     * Do停止
     */
    @Override
    protected void doStop() {
        if (selectors != null) {
            for (Selector sel : selectors) {
                try {
                    sel.close();
                } catch (IOException e) {
                    log.debug("[nio] 关闭连接失败，已忽略: {}", e.getMessage());
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
            } catch (IOException e) {
                log.debug("[nio] IO 操作失败，已忽略: {}", e.getMessage());
            }
        }
        wsConnections.clear();
        log.info("NIO HttpServer stopped");
    }

    /**
     * 远端地址未知(安全标识),不在魔法值白名单内使用字面量。
     */
    private static final String UNKNOWN_REMOTE = "address unknown";

    /**
     * 安全地获取通道的远端地址(关闭或取不到时返回 unknown)。
     *
     * @param channel 套接字通道,可为 null
     * @return 远端地址字符串
     */
    private static String safeRemote(java.nio.channels.SocketChannel channel) {
        if (channel == null) {
            return UNKNOWN_REMOTE;
        }
        try {
            return String.valueOf(channel.getRemoteAddress());
        } catch (Exception e) {
            return UNKNOWN_REMOTE;
        }
    }

    /**
     * 获取ProtocolType
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }
}
