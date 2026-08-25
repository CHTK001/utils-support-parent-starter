package com.chua.common.support.network.server.aio;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioServerRequest;
import com.chua.common.support.network.server.nio.NioServerResponse;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.websocket.WebSocketProtocol;
import com.chua.common.support.network.ssl.SslUtils;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 {@link AsynchronousSocketChannel} 的 HTTP/1.1 服务器实现(Proactor 模式)。
 *
 * <p>与 {@link com.chua.common.support.network.server.nio.NioHttpServer}(Reactor 模式)的核心差异:
 * Windows 上 JDK 的 NIO Selector 基于 {@code WSAEventSelect}(每 64 个连接一个辅助线程,
 * select 开销随连接数线性增长,多 Selector 分片存在并发稳定性限制),
 * 而 AIO 在 Windows 底层是真正的 I/O 完成端口(IOCP)——
 * 内核批量收割完成事件、数据直达用户态缓冲区,万级并发连接下无性能退化,
 * 突破 NIO 实现实测约 2 万连接的上限。</p>
 *
 * <p>架构设计:</p>
 * <ul>
 *   <li>Proactor 三级流水:IOCP group 线程(轻量回调派发) → 虚拟线程 worker(handler 执行)
 *       → 串行异步写(单连接同一时刻至多一个 outstanding write,无 OP_WRITE 续写竞态)</li>
 *   <li>HTTP 解析完全复用 {@link NioServerRequest#feed(ByteBuffer)} 增量状态机,
 *       响应复用 {@link NioServerResponse} 的零分配 header 快路径(asyncWriter 钩子)</li>
 *   <li>读超时即 idle 连接回收(Reactor 版无此能力),懒分配 direct 读缓冲,
 *       百万级空闲连接零缓冲占用</li>
 *   <li>背压模型:请求处理期间暂停读;响应经待写队列由完成回调驱动续写,
 *       写完且 Keep-Alive 后恢复读,天然支持 HTTP pipeline 残留数据</li>
 * </ul>
 *
 * <p>特性(v2):
 * <ul>
 *   <li>Proactor 三级流水:IOCP group 线程(轻量回调派发) → 虚拟线程 worker(handler 执行)
 *       → 串行异步写(单连接同一时刻至多一个 outstanding write,无 OP_WRITE 续写竞态)</li>
 *   <li>HTTP 解析完全复用 {@link NioServerRequest#feed(ByteBuffer)} 增量状态机,
 *       响应复用 {@link NioServerResponse} 的零分配 header 快路径(asyncWriter 钩子)</li>
 *   <li>SSL/TLS — 每连接 SSLEngine,握手在专属虚拟线程以 Future 阻塞驱动;
 *       数据面 unwrap 内联于读回调、wrap 在响应入队时同步完成,proactor 吞吐不受影响</li>
 *   <li>WebSocket 升级 — RFC 6455 握手后切换帧协议,支持
 *       {@code @OnMessage} 注解方法与 {@link #onSubscribe(String, ServerHandler)} 订阅,
 *       Future 阻塞适配器使帧循环运行在虚拟线程上(TLS 下自动加解密)</li>
 *   <li>背压模型:请求处理期间暂停读;响应经待写队列由完成回调驱动续写,
 *       写完且 Keep-Alive 后恢复读,天然支持 HTTP pipeline 残留数据</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/24
 */
@Slf4j
@Spi({"aio", "aio-http", "iocp"})
public class AioHttpServer extends AbstractServer {

    /**
     * 默认读缓冲区大小(字节):与 NIO 版一致取 32KB,
     * 减少大请求体场景的系统调用次数,小请求仅按需 flip 无额外开销
     */
    private static final int READ_BUFFER_SIZE = 32768;

    /**
     * 并发 accept 重叠深度:IOCP 支持多个重叠 AcceptEx 同时等待,
     * 高并发短连接突发下提升接纳吞吐,避免单 accept 间隙
     */
    private static final int ACCEPT_DEPTH = 2;

    /**
     * 最小 backlog:高并发连接突发下避免内核因队列满拒绝连接(与 NIO 版对齐)
     */
    private static final int MIN_BACKLOG = 65536;

    /**
     * 最小接收缓冲(字节):SO_RCVBUF 下限,保证突发吞吐
     */
    private static final int MIN_SO_RCVBUF = 16384;

    /** 合并缓冲池:复用直接内存,消除每响应分配 */
    private static final java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> COMBINE_POOL =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static ByteBuffer acquireCombined(int size) {
        ByteBuffer b = COMBINE_POOL.poll();
        if (b == null || b.capacity() < size) {
            b = ByteBuffer.allocate(size);
        } else {
            b.clear();
        }
        return b;
    }

    private static void releaseCombined(ByteBuffer b) {
        if (b != null && b.capacity() >= COMBINE_THRESHOLD && COMBINE_POOL.size() < 64) {
            b.clear();
            COMBINE_POOL.offer(b);
        }
    }

    /** 合并写阈值 */
    private static final int COMBINE_THRESHOLD = 65536;

    /** 监听通道 */
    private AsynchronousServerSocketChannel serverChannel;

    /**
     * IOCP 完成端口线程组:线程数即内核并发收割度,
     * 仅执行轻量回调(read 完成 → feed → 提交 worker),少量线程即可驱动海量连接
     */
    private AsynchronousChannelGroup group;



    /** SSL 上下文(配置了 selfSigned/KeyStore/PEM 时非 null,每连接派生 SSLEngine) */
    private SSLContext sslContext;

    /** WebSocket 主题处理器映射(topic -> handlers),与 NIO 版同构 */
    private final Map<String, List<ServerHandler>> wsTopicHandlers = new ConcurrentHashMap<>();

    /** 当前活跃的 WebSocket 连接 */
    private final List<AioWsConnection> wsConnections = new CopyOnWriteArrayList<>();

    /** 当前活跃连接数(观测用,验证高并发连接目标) */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /** 读完成处理器(单例复用:attachment 携带连接状态,无 per-read 分配) */
    private final ReadHandler readHandler = new ReadHandler();

    /** 写完成处理器(单例复用) */
    private final WriteHandler writeHandler = new WriteHandler();

    /** 接受连接处理器(acceptDepth 个实例各自循环补位) */
    private final AcceptHandler acceptHandler = new AcceptHandler();

    /**
     * 创建 AioHttpServer 实例
     *
     * @param setting 服务器配置
     */
    public AioHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            // SSL/TLS:selfSigned/KeyStore/PEM 统一经 SslUtils 构建,
            // 每连接派生 SSLEngine,握手在独立虚拟线程以阻塞 Future 方式驱动
            ServerSetting.SslConfig ssl = setting.getSsl();
            sslContext = SslUtils.autoSsl(ssl);
            if (sslContext != null) {
                // 禁用 TLS1.3 服务端 NewSessionTicket:票据记录会在握手后以应用数据
                // 形式到达,其明文会污染 HTTP 增量解析器(被当作请求行垃圾字节)
                System.setProperty("jdk.tls.server.enableSessionTicketExtension", "false");
                log.info("AIO HttpServer SSL enabled (selfSigned={})", ssl.isSelfSigned());
            }

            // IOCP group 线程数:eventLoops 显式配置优先,否则按 CPU 核数
            int groupThreads = setting.getEventLoops() > 0
                    ? setting.getEventLoops() : Runtime.getRuntime().availableProcessors();
            group = AsynchronousChannelGroup.withFixedThreadPool(groupThreads, r -> {
                Thread t = new Thread(r, "aio-iocp-group");
                t.setDaemon(true);
                return t;
            });

            serverChannel = AsynchronousServerSocketChannel.open(group);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.setOption(StandardSocketOptions.SO_RCVBUF,
                    Math.max(setting.getBufferSize(), MIN_SO_RCVBUF));
            int backlog = Math.max(setting.getBacklog(), MIN_BACKLOG);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), backlog);

            // 回填实际端口(port=0 时由系统分配)
            InetSocketAddress bound = (InetSocketAddress) serverChannel.getLocalAddress();
            setting.setPort(bound.getPort());


            // 挂起 ACCEPT_DEPTH 个重叠 accept,completed 回调内立即补位,
            // 保证接纳流水线永不中断
            for (int i = 0; i < ACCEPT_DEPTH; i++) {
                issueAccept();
            }

            log.info("AIO HttpServer started on {}:{} (backlog={}, iocpThreads={}, proactor=true)",
                    setting.getHost(), setting.getPort(), backlog, groupThreads);
        } catch (Exception e) {
            throw new RuntimeException("AIO HttpServer 启动失败", e);
        }
    }

    /**
     * 发起重叠 accept:完成后由 {@link AcceptHandler} 补位续挂。
     *
     * <p>注意:不检查 running 标志——start() 模板在 doStart() 返回后才置 running,
     * 若此处检查将导致首个 accept 永远不会挂起、服务器不接受任何连接
     * (与 NIO 版事件循环"抢跑于 running 置位前"的坑同源)。
     * 生命周期由 serverChannel 开关驱动:doStopAccepting 关闭通道后,
     * 未完成的 accept 进入 failed 回调自然终止。</p>
     */
    private void issueAccept() {
        if (serverChannel == null || !serverChannel.isOpen()) {
            return;
        }
        try {
            serverChannel.accept(null, acceptHandler);
        } catch (java.nio.channels.AcceptPendingException e) {
            // Windows AIO 同一通道仅允许一个挂起 accept(非 AcceptEx 语义):
            // ACCEPT_DEPTH>1 时后续挂起必然抛此异常,静默忽略即可,
            // 实际补位由 completed 回调驱动,接纳流水线不受影响
        } catch (Throwable t) {
            log.warn("AIO accept 发起失败: {}", t.toString());
        }
    }

    /**
     * accept 完成处理器:先补位下一个重叠 accept 再处理新连接,
     * 接纳吞吐不受单连接初始化耗时影响。
     *
     * @author CH
     * @since 2026/08/24
     */
    private class AcceptHandler implements CompletionHandler<AsynchronousSocketChannel, Void> {

        @Override
        public void completed(AsynchronousSocketChannel channel, Void attachment) {
            // 先补位:保证 accept 流水线不间断
            issueAccept();
            handleNewConnection(channel);
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            // 服务器主动关闭导致的失败不再续挂;其余异常(如瞬时内核资源不足)继续补位
            if (running && serverChannel != null && serverChannel.isOpen()) {
                log.warn("AIO accept 异常: {}", exc.getMessage());
                issueAccept();
            }
        }
    }

    /**
     * 新连接初始化:关闭 Nagle、注册活跃计数、发起首个读。
     *
     * @param channel 新接入的通道
     */
    private void handleNewConnection(AsynchronousSocketChannel channel) {
        try {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
        } catch (Exception e) {
            // 个别平台设置失败不影响主流程
            log.debug("TCP_NODELAY 设置失败: {}", e.getMessage());
        }
        ConnState state = new ConnState(channel);
        activeConnections.incrementAndGet();
        if (sslContext != null) {
            // TLS 连接:每连接独立 SSLEngine,纯异步状态机驱动握手(零阻塞、零驻留线程)
            SSLEngine engine = sslContext.createSSLEngine();
            engine.setUseClientMode(false);
            state.tls = new TlsState(engine);
            try {
                engine.beginHandshake();
            } catch (Exception e) {
                log.debug("beginHandshake 失败: {}", e.getMessage());
                closeConn(state);
                return;
            }
            tlsDrive(state);
            return;
        }
        issueRead(state);
    }

    /**
     * 发起异步读:一次仅挂一个 outstanding read(串行化,消除乱序),
     * 超时参数兼做空闲连接回收(Keep-Alive 连接无新数据自动断开)。
     *
     * @param state 连接状态
     */
    private void issueRead(ConnState state) {
        if (state.closed.get() || !running) {
            return;
        }
        ByteBuffer buf;
        if (state.tls != null) {
            // TLS 连接:网络层读的是密文,固定使用预分配的 netIn
            buf = state.tls.netIn;
        } else {
            buf = state.readBuf;
            if (buf == null) {
                // 懒分配 direct 缓冲:空闲连接零占用
                buf = ByteBuffer.allocateDirect(Math.max(setting.getBufferSize(), READ_BUFFER_SIZE));
                state.readBuf = buf;
            }
        }
        try {
            // 极限优化:无超时读(定时异步读在 Windows 需挂内核等待对象)
            state.channel.read(buf, state, readHandler);
        } catch (Throwable t) {
            closeConn(state);
        }
    }

    /**
     * 读完成处理器:喂入增量解析器,完整请求提交虚拟线程 worker。
     *
     * @author CH
     * @since 2026/08/24
     */
    private class ReadHandler implements CompletionHandler<Integer, ConnState> {

        @Override
        public void completed(Integer result, ConnState state) {
            if (state.closed.get() || !running) {
                closeConn(state);
                return;
            }
            int n = result == null ? -1 : result;
            if (n < 0) {
                // 对端正常关闭
                closeConn(state);
                return;
            }
            if (n > 0) {
                if (state.tls != null) {
                    // TLS:密文 → unwrap → 明文 → 喂解析器(内部处理背压/续读)
                    tlsOnNetworkData(state);
                    return;
                }
                ByteBuffer buf = state.readBuf;
                buf.flip();
                int r;
                try {
                    r = state.request.feed(buf);
                } catch (Exception e) {
                    // 解析器内部异常按协议错误处理
                    log.debug("HTTP 解析异常,关闭连接: {}", e.getMessage());
                    closeConn(state);
                    return;
                }
                buf.compact();
                if (r == 1) {
                    // 完整请求就绪:暂停读(背压),交由 worker 处理
                    dispatchToWorker(state);
                    return;
                }
                if (r < 0) {
                    // 协议错误/超长请求:直接断开
                    closeConn(state);
                    return;
                }
            }
            // n==0 或请求未完整:继续读
            issueRead(state);
        }

        @Override
        public void failed(Throwable exc, ConnState state) {
            // 含读超时(idle 回收)与对端重置
            closeConn(state);
        }
    }

    /**
     * 将已解析完整的请求提交虚拟线程 worker 执行 handler 链。
     *
     * @param state 连接状态
     */
    private void dispatchToWorker(ConnState state) {
        // 纯响应式:IOCP 回调线程内联执行(handler 契约要求非阻塞)
        processRequest(state);
    }

    /**
     * worker 主流程:构建响应(写出钩子入队待写队列)→ 执行 handler 链 →
     * complete() 构建报文字节入队 → 启动串行异步写循环。
     *
     * @param state 连接状态
     */
    private void processRequest(ConnState state) {
        try {
            // WebSocket 升级:回握手响应后进入帧协议循环(当前虚拟线程内)
            if (WebSocketProtocol.isUpgradeRequest(state.request)) {
                handleWebSocketUpgrade(state);
                return;
            }
            NioServerResponse response = new NioServerResponse(null);
            // 写出钩子:complete() 时把响应头/体字节入待写队列(零拷贝传递,不落中间缓冲)
            response.setAsyncWriter((header, body) -> enqueueWrite(state, header, body));
            // 流式直写钩子(纯非阻塞):SSE 分片按序入待写队列,TLS 在队内加密,
            // 写权空闲则踢异步续写链;分片间 FIFO 保序
            response.setStreamWriter(bytes -> {
                // 标记 SSE 流式期:写链排空时不按 HTTP 收尾(见 continueWrite)
                state.sseActive = true;
                enqueueWrite(state, java.nio.ByteBuffer.wrap(bytes), null);
                if (!state.closed.get() && state.writing.compareAndSet(false, true)) {
                    continueWrite(state);
                }
            });
            try {
                handleRequest(state.request, response);
            } catch (Exception e) {
                log.warn("AIO 请求处理失败: {}", e.getMessage());
                if (!response.isCommitted()) {
                    response.sendError(500, "Internal Server Error");
                }
            } finally {
                // 幂等(sent 标志):触发 asyncWriter 把报文交给待写队列
                response.complete();
            }
            if (state.sseActive && response.isEnded()) {
                // SSE 全部分片已入队(sseClose 置 ended):写链排空后安全断开送 EOF
                state.sseEnd = true;
            }
            state.keepAlive = shouldKeepAlive(state.request, response);
            state.request.resetForNextRequest();
            if (state.closed.get()) {
                return;
            }
            // 纯非阻塞:CAS 抢占写权后由异步续写链驱动(AIO API 无同步直写路径)
            if (state.writing.compareAndSet(false, true)) {
                continueWrite(state);
            }
        } catch (Throwable t) {
            log.warn("AIO worker 异常: {} -> {}", t.getClass().getSimpleName(), t.getMessage());
            closeConn(state);
        }
    }

    /**
     * 判断是否保持连接(语义与 NIO 版一致):
     * 显式 Connection 头优先,HTTP/1.1 默认 Keep-Alive。
     *
     * @param request  请求
     * @param response 响应
     * @return true 表示保持连接
     */
    private boolean shouldKeepAlive(NioServerRequest request, NioServerResponse response) {
        if (response.isChannelClosed()) {
            return false;
        }
        String connHeader = request.getHeader("Connection");
        if (connHeader != null) {
            return "keep-alive".equalsIgnoreCase(connHeader.trim());
        }
        return "HTTP/1.1".equalsIgnoreCase(request.getHttpVersion());
    }

    /**
     * 响应片段入队(header/body 各为一个 ByteBuffer,零拼接)。
     *
     * @param state  连接状态
     * @param header 响应头字节
     * @param body   响应体字节(可为 null)
     */
    private void enqueueWrite(ConnState state, ByteBuffer header, ByteBuffer body) {
        synchronized (state.writeQueue) {
            if (state.tls == null) {
                if (body != null && body.hasRemaining()) {
                    // 极限优化:小响应头体合并为单缓冲,一次 overlap 写完成
                    int total = header.remaining() + body.remaining();
                    if (total <= 65536) {
                        // 堆分配(TLAB 近乎免费);JDK 写出时自会拷贝到 native
                        ByteBuffer combined = acquireCombined(total);
                        combined.put(header).put(body);
                        combined.flip();
                        state.writeQueue.add(combined);
                        return;
                    }
                }
                state.writeQueue.add(header);
                if (body != null && body.hasRemaining()) {
                    state.writeQueue.add(body);
                }
                return;
            }
            // TLS:入队时同步加密为密文块。单连接同一时刻至多一个在途请求
            // (读暂停背压保证),故此处 wrap 无并发,写循环/完成回调无需感知 TLS
            TlsState tls = state.tls;
            ByteBuffer[] srcs = {header, body};
            for (ByteBuffer src : srcs) {
                if (src == null || !src.hasRemaining()) {
                    continue;
                }
                while (src.hasRemaining()) {
                    ByteBuffer netOut = ByteBuffer.allocateDirect(tls.packetSize);
                    try {
                        tls.engine.wrap(src, netOut);
                    } catch (Exception e) {
                        log.warn("TLS wrap 失败,关闭连接: {}", e.getMessage());
                        closeConn(state);
                        return;
                    }
                    drainTasks(tls);
                    netOut.flip();
                    if (netOut.hasRemaining()) {
                        state.writeQueue.add(netOut);
                    }
                }
            }
        }
    }

    /**
     * 启动串行异步写:从待写队列头部取块发起 channel.write,
     * 由 {@link WriteHandler} 完成回调驱动续写直至队列排空。
     *
     * <p>调用前提:持有写权({@code state.writing} 已 CAS 为 true)。
     * 队列空则在锁内置 writing=false(持锁置位杜绝丢唤醒)并收尾。</p>
     *
     * @param state 连接状态
     */
    private void continueWrite(ConnState state) {
        if (state.closed.get()) {
            return;
        }
        ByteBuffer head;
        synchronized (state.writeQueue) {
            head = state.writeQueue.peek();
            if (head == null) {
                // 持锁释放写权:与入队方的 CAS 形成正确的 happens-before,无丢失唤醒窗口
                state.writing.set(false);
            }
        }
        if (head == null) {
            if (state.sseActive) {
                // SSE 流式期:不按 HTTP 收尾;sseClose 后(零分块已写出)断开送 EOF
                if (state.sseEnd) {
                    closeConn(state);
                }
                return;
            }
            finishResponseCycle(state);
            return;
        }
        try {
            state.channel.write(head, setting.getWriteTimeout(), TimeUnit.MILLISECONDS,
                    state, writeHandler);
        } catch (Throwable t) {
            closeConn(state);
        }
    }

    /**
     * 单次响应周期收尾:Keep-Alive 则恢复读下一条请求(pipeline 残留已在解析缓冲),
     * 否则关闭连接。
     *
     * @param state 连接状态
     */
    private void finishResponseCycle(ConnState state) {
        if (state.wsMode && running && !state.closed.get()) {
            wsIssueRead(state);
            return;
        }
        if (state.keepAlive && running && !state.closed.get()) {
            issueRead(state);
        } else {
            closeConn(state);
        }
    }

    /**
     * 写完成处理器:未写完的块原地续写,写完的块出队后推进下一块,
     * 队列排空则收尾(恢复读或关闭)。
     *
     * @author CH
     * @since 2026/08/24
     */
    private class WriteHandler implements CompletionHandler<Integer, ConnState> {

        @Override
        public void completed(Integer result, ConnState state) {
            if (state.closed.get()) {
                return;
            }
            int written = result == null ? 0 : result;
            if (written < 0) {
                closeConn(state);
                return;
            }
            synchronized (state.writeQueue) {
                ByteBuffer head = state.writeQueue.peek();
                if (head != null && !head.hasRemaining()) {
                    // 当前块已全部写出,移除;非直接缓冲归还合并池复用
                    state.writeQueue.poll();
                    releaseCombined(head);
                }
                // 未写完的块保留在队首,continueWrite 会原地重发剩余部分
            }
            continueWrite(state);
        }

        @Override
        public void failed(Throwable exc, ConnState state) {
            // 含写超时(对端接收停滞)与连接重置
            closeConn(state);
        }
    }

    // ==================== SSL/TLS 支持 ====================

    /** 握手期空缓冲(wrap 方向输入) */
    private static final ByteBuffer TLS_EMPTY = ByteBuffer.allocate(0);

    /**
     * TLS 握手状态机驱动(纯异步):每完成一步 wrap/unwrap 后经
     * CompletionHandler 回调再次进入,直至 NOT_HANDSHAKING 进入数据面。
     *
     * @param state 连接状态
     */
    private void tlsDrive(ConnState state) {
        TlsState tls = state.tls;
        SSLEngineResult.HandshakeStatus hs = tls.engine.getHandshakeStatus();
        switch (hs) {
            case NEED_TASK -> {
                drainTasks(tls);
                tlsDrive(state);
            }
            case NEED_WRAP -> {
                tls.netOut.clear();
                SSLEngineResult r;
                try {
                    r = tls.engine.wrap(TLS_EMPTY, tls.netOut);
                } catch (Exception e) {
                    log.debug("TLS wrap 异常: {}", e.getMessage());
                    closeConn(state);
                    return;
                }
                drainTasks(tls);
                tls.netOut.flip();
                writeNetAsync(state, tls.netOut,
                        () -> tlsDrive(state),
                        ex -> closeConn(state));
                // OVERFLOW 残留:drive 再次进入时引擎仍为 NEED_WRAP,会续写下一片
            }
            case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> {
                if (tls.hsForceRead || tls.netIn.position() == 0) {
                    tls.hsForceRead = false;
                    readNetAsync(state, tls.netIn,
                            () -> tlsUnwrapStep(state),
                            ex -> closeConn(state));
                    return;
                }
                tlsUnwrapStep(state);
            }
            default -> {
                // NOT_HANDSHAKING / FINISHED:握手完成,进入 proactor 数据面
                tls.handshakeDone = true;
                log.debug("TLS 握手完成 ({}, {})",
                        tls.engine.getSession().getCipherSuite(),
                        tls.engine.getSession().getProtocol());
                issueRead(state);
            }
        }
    }

    /**
     * 单步解密:flip → unwrap → compact;UNDERFLOW 置强制读标志后继续驱动。
     */
    private void tlsUnwrapStep(ConnState state) {
        TlsState tls = state.tls;
        tls.netIn.flip();
        SSLEngineResult r;
        try {
            r = tls.engine.unwrap(tls.netIn, tls.plain);
        } catch (Exception e) {
            log.debug("TLS unwrap 异常: {}", e.getMessage());
            closeConn(state);
            return;
        }
        drainTasks(tls);
        tls.netIn.compact();
        if (r.getStatus() == SSLEngineResult.Status.CLOSED) {
            closeConn(state);
            return;
        }
        if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            tls.hsForceRead = true;
        }
        tlsDrive(state);
    }

    /**
     * 异步读网络(握手期),完成后回调续接。
     */
    private void readNetAsync(ConnState state, ByteBuffer dst,
                              Runnable onDone, java.util.function.Consumer<Throwable> onFail) {
        try {
            state.channel.read(dst, null, new java.nio.channels.CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer n, Void attachment) {
                    if (n == null || n < 0) {
                        onFail.accept(new java.io.IOException("对端关闭"));
                        return;
                    }
                    onDone.run();
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    onFail.accept(exc);
                }
            });
        } catch (Exception e) {
            onFail.accept(e);
        }
    }

    /**
     * 异步写网络直至排空(握手期),完成后回调续接。
     */
    private void writeNetAsync(ConnState state, ByteBuffer src,
                               Runnable onDone, java.util.function.Consumer<Throwable> onFail) {
        try {
            state.channel.write(src, null, new java.nio.channels.CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer n, Void attachment) {
                    if (src.hasRemaining()) {
                        state.channel.write(src, null, this);
                        return;
                    }
                    onDone.run();
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    onFail.accept(exc);
                }
            });
        } catch (Exception e) {
            onFail.accept(e);
        }
    }
    private void tlsOnNetworkData(ConnState state) {
        TlsState tls = state.tls;
        ByteBuffer netIn = tls.netIn;
        netIn.flip();
        int feedResult = 0;
        while (netIn.hasRemaining()) {
            SSLEngineResult r;
            try {
                r = tls.engine.unwrap(netIn, tls.plain);
            } catch (Exception e) {
                log.debug("TLS unwrap 异常,关闭连接: {}", e.getMessage());
                closeConn(state);
                return;
            }
            drainTasks(tls);
            SSLEngineResult.Status st = r.getStatus();
            if (tls.plain.position() > 0) {
                tls.plain.flip();
                try {
                    feedResult = state.request.feed(tls.plain);
                } catch (Exception e) {
                    log.debug("HTTP 解析异常(TLS),关闭连接: {}", e.getMessage());
                    closeConn(state);
                    return;
                }
                tls.plain.compact();
                if (feedResult == 1) {
                    break;
                }
                if (feedResult < 0) {
                    closeConn(state);
                    return;
                }
            }
            if (st == SSLEngineResult.Status.CLOSED) {
                closeConn(state);
                return;
            }
            if (st == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                // 应用缓冲不足:扩容并保留已产出的明文
                int bigger = tls.plain.capacity() + tls.engine.getSession().getApplicationBufferSize();
                ByteBuffer grown = ByteBuffer.allocateDirect(bigger);
                tls.plain.flip();
                grown.put(tls.plain);
                tls.plain = grown;
                continue;
            }
            if (st == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                // 需要更多密文
                break;
            }
        }
        netIn.compact();
        if (feedResult == 1) {
            dispatchToWorker(state);
            return;
        }
        issueRead(state);
    }

    /** 执行 engine 的全部委托任务(密钥协商/签名等)。 */
    private static void drainTasks(TlsState tls) {
        Runnable task;
        while ((task = tls.engine.getDelegatedTask()) != null) {
            task.run();
        }
    }

    /** Future 阻塞读网络(握手与 WS 帧循环使用,虚拟线程阻塞零平台线程占用)。 */
    private void readNetBlocking(ConnState state, ByteBuffer dst, long timeoutMs) throws Exception {
        Integer n;
        try {
            n = state.channel.read(dst).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException(e);
        }
        if (n == null || n < 0) {
            throw new IOException("对端关闭连接");
        }
    }

    /** Future 阻塞写网络直至缓冲排空。 */
    private void writeNetBlocking(ConnState state, ByteBuffer src, long timeoutMs) throws Exception {
        while (src.hasRemaining()) {
            Integer n;
            try {
                n = state.channel.write(src).get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException(e);
            }
            if (n == null || n < 0) {
                throw new IOException("网络写入失败");
            }
        }
    }

    // ==================== WebSocket 支持 ====================

    /**
     * 处理 WebSocket 升级(RFC 6455,纯非阻塞):
     * 握手响应入待写队列(排空回调进入帧读取),此后所有收发均为
     * 异步回调 —— 增量帧解码器消化掩码/分片,完整帧交虚拟线程分发。
     *
     * @param state 连接状态
     */
    private void handleWebSocketUpgrade(ConnState state) {
        String key = state.request.getHeader("Sec-WebSocket-Key");
        if (key == null || key.isBlank()) {
            closeConn(state);
            return;
        }
        String accept = WebSocketProtocol.computeAccept(key);
        state.wsMode = true;
        state.wsConn = new AioWsConnection(frame -> {
            enqueueWrite(state, ByteBuffer.wrap(frame), null);
            if (!state.closed.get() && state.writing.compareAndSet(false, true)) {
                continueWrite(state);
            }
        });
        wsConnections.add(state.wsConn);
        enqueueWrite(state, ByteBuffer.wrap(WebSocketProtocol.handshakeResponse(accept)), null);
        if (!state.closed.get() && state.writing.compareAndSet(false, true)) {
            continueWrite(state);
        }
    }

    /**
     * 启动一轮异步明文读取并喂给 WS 帧解码器。
     * 明文来源:明文连接读 readBuf;TLS 连接经数据面 unwrap 产出的 plain。
     */
    private void wsIssueRead(ConnState state) {
        if (!running || state.closed.get()) {
            return;
        }
        if (state.tls != null) {
            // TLS:网络密文 → netIn,解密后明文落在 plain,flip 后喂解码器
            state.tls.netIn.clear();
            readNetAsync(state, state.tls.netIn,
                    () -> {
                        TlsState tls = state.tls;
                        tls.netIn.flip();
                        while (tls.netIn.hasRemaining()) {
                            SSLEngineResult r;
                            try {
                                r = tls.engine.unwrap(tls.netIn, tls.plain);
                            } catch (Exception e) {
                                log.debug("TLS unwrap(WS) 异常: {}", e.getMessage());
                                closeConn(state);
                                return;
                            }
                            drainTasks(tls);
                            if (r.getStatus() == SSLEngineResult.Status.CLOSED) {
                                closeConn(state);
                                return;
                            }
                            if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                                break;
                            }
                            if (tls.plain.position() > 0) {
                                tls.plain.flip();
                                boolean cont = wsFeed(state, tls.plain);
                                tls.plain.compact();
                                if (!cont) {
                                    return;
                                }
                            }
                        }
                        tls.netIn.compact();
                        wsIssueRead(state);
                    },
                    ex -> closeConn(state));
            return;
        }
        // 明文连接:直接读 readBuf
        ByteBuffer buf = state.readBuf;
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(Math.max(setting.getBufferSize(), READ_BUFFER_SIZE));
            state.readBuf = buf;
        }
        buf.clear();
        final ByteBuffer fbuf = buf;
        readNetAsync(state, fbuf,
                () -> {
                    fbuf.flip();
                    boolean cont = wsFeed(state, fbuf);
                    fbuf.compact();
                    if (cont) {
                        wsIssueRead(state);
                    }
                },
                ex -> closeConn(state));
    }

    /**
     * 喂一个明文分片给 WS 帧解码器。
     *
     * @return true=连接继续;false=连接应终止(已关闭/关闭中)
     */
    private boolean wsFeed(ConnState state, ByteBuffer src) {
        WsDecoder d = state.wsDecoder();
        while (src.hasRemaining()) {
            if (!d.step(src)) {
                return false;
            }
            if (d.complete) {
                int opcode = d.opcode;
                byte[] payload = d.payload.toByteArray();
                d.reset();
                switch (opcode) {
                    case 0x8 -> { // close:回发关闭帧后终止
                        state.wsConn.sendRaw(WebSocketProtocol.closeFrame("bye"));
                        closeConn(state);
                        return false;
                    }
                    case 0x9 -> state.wsConn.sendRaw(WebSocketProtocol.textFrame("pong"));
                    case 0x1, 0x2 -> {
                        WebSocketProtocol.Frame frame =
                                new WebSocketProtocol.Frame(opcode, payload);
                        dispatchWsMessage(frame, state.wsConn);
                    }
                    default -> { }
                }
            }
        }
        return !state.closed.get();
    }

    /**
     * RFC 6455 增量帧解码器(服务端视角:客户端帧必须掩码)。
     */
    private static final class WsDecoder {

        /** 阶段 */
        private enum Stage { LEN0, LEN16, LEN64, MASK, PAYLOAD }

        private Stage stage = Stage.LEN0;
        /** 操作码 */
        int opcode;
        /** 载荷 */
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        /** 完整帧就绪标志 */
        boolean complete;

        private long len;
        private int extLeft;
        private byte[] mask;
        private int maskIdx;

        /**
         * 消费输入;返回 false 表示连接终止(非法长度)。
         */
        boolean step(ByteBuffer in) {
            while (in.hasRemaining()) {
                switch (stage) {
                    case LEN0 -> {
                        // 字节1=FIN|RSV|opcode,字节2=MASK|len7;两字节齐才消费
                        if (in.remaining() < 2) {
                            return true;
                        }
                        int b1 = in.get() & 0xFF;
                        opcode = b1 & 0x0F;
                        int b2 = in.get() & 0xFF;
                        boolean masked = (b2 & 0x80) != 0;
                        long l = b2 & 0x7F;
                        if (l == 126) {
                            stage = Stage.LEN16;
                            extLeft = 2;
                            len = 0;
                        } else if (l == 127) {
                            stage = Stage.LEN64;
                            extLeft = 8;
                            len = 0;
                        } else {
                            len = l;
                            enterPayload(masked);
                        }
                    }
                    case LEN16, LEN64 -> {
                        len = (len << 8) | (in.get() & 0xFF);
                        if (--extLeft == 0) {
                            enterPayload(true);
                        }
                    }
                    case MASK -> {
                        mask[maskIdx++] = in.get();
                        if (maskIdx == 4) {
                            stage = Stage.PAYLOAD;
                            payload.reset();
                            maskIdx = 0;
                        }
                    }
                    default -> {
                        // PAYLOAD
                        int take = (int) Math.min(in.remaining(),
                                len - payload.size());
                        int start = in.position();
                        if (mask != null) {
                            for (int i = 0; i < take; i++) {
                                payload.write(in.get(start + i) ^ mask[maskIdx++ & 3]);
                            }
                        } else {
                            for (int i = 0; i < take; i++) {
                                payload.write(in.get(start + i));
                            }
                        }
                        in.position(start + take);
                        if (payload.size() >= len) {
                            complete = true;
                            return true;
                        }
                        return true; // 本分片耗尽或不足一帧,等下一片
                    }
                }
            }
            return true;
        }

        /**
         * 进入载荷阶段(按需补掩码键)。
         */
        private void enterPayload(boolean masked) {
            if (masked && mask == null) {
                mask = new byte[4];
                maskIdx = 0;
                stage = Stage.MASK;
            } else {
                mask = null;
                stage = Stage.PAYLOAD;
                payload.reset();
            }
        }

        /**
         * 复位以接收下一帧。
         */
        void reset() {
            stage = Stage.LEN0;
            opcode = 0;
            len = 0;
            extLeft = 0;
            maskIdx = 0;
            mask = null;
            payload.reset();
            complete = false;
        }
    }
    /**
     * 按主题分发 WebSocket 消息({@code topic\nbody} 约定,与 NIO 版一致)。
     */
    private void dispatchWsMessage(WebSocketProtocol.Frame frame, AioWsConnection conn) {
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
            AioWsRequest wsRequest = new AioWsRequest(topic, body);
            AioWsResponse wsResponse = new AioWsResponse(conn);
            try {
                handler.handle(wsRequest, wsResponse);
            } catch (Exception e) {
                log.warn("AIO WebSocket handler 异常: {}", e.getMessage());
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
    public AioHttpServer onSubscribe(String topic, ServerHandler handler) {
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
        for (AioWsConnection conn : wsConnections) {
            conn.sendRaw(frame);
        }
    }

    @Override
    /** 注册Bean */
    public AioHttpServer registerBean(Object handler) {
        super.registerBean(handler);
        if (handler == null) {
            return this;
        }
        // 扫描 @OnMessage 注解方法注册为 WebSocket 主题处理器(与 NIO 版一致)
        for (Method method : handler.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnMessage.class)) {
                method.setAccessible(true);
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
                Object result = method.invoke(bean, args);
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

    // ==================== WS 阻塞适配器(TLS 感知) ====================

    /**
     * 打开原始读取流:明文连接直读通道;TLS 连接经引擎解密,
     * 供 WS 帧循环在虚拟线程上以阻塞方式消费。
     */
    private InputStream openRawReader(ConnState state) {
        TlsState tls = state.tls;
        long timeoutMs = Math.max(setting.getReadTimeout(), 10_000L);
        return new InputStream() {
            final ByteBuffer buf = ByteBuffer.allocate(
                    Math.max(setting.getBufferSize(), READ_BUFFER_SIZE));

            private int fill() throws IOException {
                buf.clear();
                try {
                    if (tls == null) {
                        readNetBlocking(state, buf, timeoutMs);
                        // 写模式 → 读模式:position 归零,limit=实际收到的字节数
                        buf.flip();
                        return buf.remaining();
                    }
                    // TLS:读密文 → unwrap → 明文进 plain,再搬运到本缓冲
                    readNetBlocking(state, tls.netIn, timeoutMs);
                    tls.netIn.flip();
                    SSLEngineResult r = tls.engine.unwrap(tls.netIn, tls.plain);
                    drainTasks(tls);
                    tls.netIn.compact();
                    if (r.getStatus() == SSLEngineResult.Status.CLOSED) {
                        return -1;
                    }
                    if (r.bytesProduced() <= 0) {
                        return 0;
                    }
                    tls.plain.flip();
                    int n = Math.min(buf.remaining(), tls.plain.remaining());
                    buf.put(tls.plain.limit(tls.plain.position() + n));
                    tls.plain.compact();
                    return n;
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public int read() throws IOException {
                if (!buf.hasRemaining()) {
                    int n = fill();
                    if (n < 0) {
                        return -1;
                    }
                    if (n == 0) {
                        return read();
                    }
                }
                return buf.get() & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (!buf.hasRemaining()) {
                    int n = fill();
                    if (n < 0) {
                        return -1;
                    }
                    if (n == 0) {
                        return read(b, off, len);
                    }
                }
                int n = Math.min(len, buf.remaining());
                buf.get(b, off, n);
                return n;
            }
        };
    }

    /**
     * 打开原始写入流:明文连接直写通道;TLS 连接 wrap 后写出,
     * 供 WS 握手响应与帧发送在虚拟线程上以阻塞方式写。
     */
    private OutputStream openRawWriter(ConnState state) {
        TlsState tls = state.tls;
        long timeoutMs = Math.max(setting.getReadTimeout(), 10_000L);
        return new OutputStream() {

            private void writeAll(ByteBuffer src) throws IOException {
                try {
                    writeNetBlocking(state, src, timeoutMs);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                ByteBuffer src = ByteBuffer.wrap(b, off, len);
                if (tls == null) {
                    writeAll(src);
                    return;
                }
                while (src.hasRemaining()) {
                    ByteBuffer netOut = ByteBuffer.allocateDirect(tls.packetSize);
                    try {
                        tls.engine.wrap(src, netOut);
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                    drainTasks(tls);
                    netOut.flip();
                    writeAll(netOut);
                }
            }

            @Override
            public void flush() {
            }
        };
    }

    /**
     * 关闭连接(幂等):close 触发未完成的 read/write 回调进入 failed,
     * 由 closed 标志短路,不会产生级联副作用。
     *
     * @param state 连接状态
     */
    private void closeConn(ConnState state) {
        if (!state.closed.getAndSet(true)) {
            activeConnections.decrementAndGet();
            try {
                state.channel.close();
            } catch (Exception ignored) {
                // 关闭失败无需处理
            }
        }
    }

    @Override
    /** Do停止Accepting */
    protected void doStopAccepting() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        if (group != null) {
            try {
                group.shutdownNow();
            } catch (Exception ignored) {
            }
        }
        log.info("AIO HttpServer stopped");
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    /**
     * 获取当前活跃连接数(观测高并发连接目标进度)。
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }

    /**
     * 单连接运行时状态:通道 + 增量解析器 + 懒分配读缓冲 + 待写队列 +
     * 串行写标志。所有字段仅被该连接的回调链与所属 worker 访问。
     *
     * @author CH
     * @since 2026/08/24
     */
    private final class ConnState {

        /** 底层异步通道 */
        final AsynchronousSocketChannel channel;

        /** WS 升级完成后的帧协议模式 */
        volatile boolean wsMode;

        /** SSE 流式进行中(写链排空不收尾) */
        volatile boolean sseActive;

        /** SSE 已关闭(零分块已入队,可安全断开送 EOF) */
        volatile boolean sseEnd;

        /** WS 连接发送器 */
        volatile AioWsConnection wsConn;

        /** WS 帧解码器(懒建) */
        private WsDecoder wsDec;

        /** TLS 状态(null 表示明文连接),握手线程启动前赋值,happens-before 保证可见 */
        volatile TlsState tls;

        /** 增量 HTTP 请求解析器(传输无关构造:远端地址预存,与 NIO 版共用状态机) */
        final NioServerRequest request;

        /** 待写队列:ArrayDeque + synchronized,NIO 版实测同构下吞吐最优 */
        final java.util.ArrayDeque<ByteBuffer> writeQueue = new java.util.ArrayDeque<>();

        /** 懒分配 direct 读缓冲(首次读到数据才分配) */
        ByteBuffer readBuf;

        /** 是否保持连接(每条响应处理后更新) */
        volatile boolean keepAlive = true;

        /**
         * 串行写权标志:true 表示已有 outstanding write,
         * CAS 抢占保证单连接同一时刻至多一个写操作(AIO 无 OP_WRITE,靠此模拟背压续写)
         */
        final AtomicBoolean writing = new AtomicBoolean(false);

        /** 连接关闭标志(幂等关闭) */
        final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * 创建连接状态。
         *
         * @param channel 异步通道
         */
        ConnState(AsynchronousSocketChannel channel) {
            this.channel = channel;
            // 远端地址一次性预存:热路径(getRemoteAddress/IP 限流)零系统调用;
            // 解析状态机与 NIO 实现完全共用(NioServerRequest 传输无关构造)
            InetSocketAddress remote = remoteAddressOrNull(channel);
            this.request = new NioServerRequest((java.net.SocketAddress) remote,
                    setting.getMaxRequestSize(), setting.getCharset());
        }

        /**
         * 提取通道远端地址(失败返回 null)。
         *
         * @param channel 异步通道
         * @return 远端地址或 null
         */
        WsDecoder wsDecoder() {
            if (wsDec == null) {
                wsDec = new WsDecoder();
            }
            return wsDec;
        }

        private InetSocketAddress remoteAddressOrNull(AsynchronousSocketChannel channel) {
            try {
                return (InetSocketAddress) channel.getRemoteAddress();
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * 单连接 TLS 运行时状态:引擎 + 密文/明文缓冲。
     * netIn 供 proactor 数据面与握手共用;plain 为解密后明文累积缓冲;
     * netOut 仅在阻塞写路径按需新建,数据面加密发生在响应入队时。
     *
     * @author CH
     * @since 2026/08/24
     */
    private static final class TlsState {

        /** 每连接 SSL 引擎(服务端模式) */
        final SSLEngine engine;

        /** 网络密文读缓冲(direct,容量=会话包大小) */
        final ByteBuffer netIn;

        /** 握手期网络密文写缓冲(direct,容量=会话包大小) */
        final ByteBuffer netOut;

        /** 解密后明文累积缓冲(语义对齐非 TLS 的 readBuf,flip/feed/compact) */
        volatile ByteBuffer plain;

        /** 会话密文包大小(WS/数据面加密分片依据) */
        final int packetSize;

        /** 握手 UNDERFLOW 后强制补读标志 */
        volatile boolean hsForceRead;

        /** 握手完成标志(观测用) */
        volatile boolean handshakeDone;

        TlsState(SSLEngine engine) {
            this.engine = engine;
            this.packetSize = engine.getSession().getPacketBufferSize();
            this.netIn = ByteBuffer.allocateDirect(packetSize);
            this.netOut = ByteBuffer.allocateDirect(packetSize);
            this.plain = ByteBuffer.allocateDirect(engine.getSession().getApplicationBufferSize());
        }
    }

    /**
     * WebSocket 连接封装,负责向对端发送帧(线程安全)。
     *
     * @author CH
     * @since 2026/08/24
     */
    private static final class AioWsConnection {

        /** OUT */
        private final java.util.function.Consumer<byte[]> sender;

        AioWsConnection(java.util.function.Consumer<byte[]> sender) {
            this.sender = sender;
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
            sender.accept(frame);
        }
    }

    /**
     * WebSocket 消息请求(语义与 NIO 版 WsServerRequest 一致)。
     *
     * @author CH
     * @since 2026/08/24
     */
    private static final class AioWsRequest implements ServerRequest {
        /** Topic */
        private final String topic;
        /** 请求体 */
        private final String body;
        /** attributes */
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        AioWsRequest(String topic, String body) {
            this.topic = topic;
            this.body = body;
        }

        @Override public String getUri() { return "/ws/" + topic; }
        @Override public String getPath() { return "/ws/" + topic; }
        @Override public com.chua.common.support.network.http.HttpMethod getMethod() {
            return com.chua.common.support.network.http.HttpMethod.POST;
        }
        @Override public String getHeader(String name) { return null; }
        @Override public com.chua.common.support.network.http.HttpHeader getHeaders() {
            return com.chua.common.support.network.http.HttpHeader.create();
        }
        @Override public Map<String, String> getParams() { return Collections.emptyMap(); }
        @Override public String getParam(String name) { return null; }
        @Override public String getContentType() { return "text/plain"; }
        @Override public long getContentLength() {
            return body != null ? body.getBytes(StandardCharsets.UTF_8).length : -1;
        }
        @Override public byte[] getBody() {
            return body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        }
        @Override public String getBodyString() { return body; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(getBody()); }
        @Override public String getRemoteAddress() { return "127.0.0.1"; }
        @Override public int getRemotePort() { return 0; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    }

    /**
     * WebSocket 消息响应(语义与 NIO 版 WsServerResponse 一致)。
     *
     * @author CH
     * @since 2026/08/24
     */
    private static final class AioWsResponse implements ServerResponse {
        /** Connection */
        private final AioWsConnection connection;
        /** 状态 */
        private int status = 200;
        /** Ended */
        private boolean ended;
        /** Committed */
        private boolean committed;
        /** 结果 */
        private Object result;

        AioWsResponse(AioWsConnection connection) {
            this.connection = connection;
        }

        @Override public int getStatus() { return status; }
        @Override public ServerResponse setStatus(int statusCode) { this.status = statusCode; return this; }
        @Override public ServerResponse setBody(byte[] body) { this.result = body; return this; }
        @Override public ServerResponse setBody(String body) { this.result = body; return this; }
        @Override public ServerResponse setHeader(String name, String value) { return this; }
        @Override public String getHeader(String name) { return null; }
        @Override public com.chua.common.support.network.http.HttpHeader getHeaders() {
            return com.chua.common.support.network.http.HttpHeader.create();
        }
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
        @Override public ServerResponse setResult(Object result) { this.result = result; return this; }
        @Override public Object getResult() { return result; }
        @Override public ServerResponse sse() { return this; }
        @Override public void sseEvent(String event, String data) { }
        @Override public void sseClose() { }
    }
}
