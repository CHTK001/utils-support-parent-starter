// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server;

import com.chua.common.support.network.PortBindRetry;
import com.chua.common.support.network.annotations.ResponseConverter;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.filter.*;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.http.ConfigServer;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.resolver.HandlerMethodArgumentResolver;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.DefaultObjectContext;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.ObjectContextConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.channels.NetworkChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务器抽象基类，协议无关。
 * <p>提供：
 * <ul>
 *   <li>生命周期 — {@link #start()} / {@link #stop()} / {@link #doStart()} / {@link #doStop()}</li>
 *   <li>过滤器管理 — 内置 GzipFilter / CorsFilter + SPI 发现 + 用户自定义(访问日志/链路追踪需显式开启)</li>
 *   <li>请求处理 — {@link #handleRequest(ServerRequest, ServerResponse)} 统一执行链 + 指标 + 限流</li>
 *   <li>运行时指标 — {@link #getMetrics()} 提供请求计数、活跃数、错误数</li>
 * </ul>
 *
 * @version 2.1
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
public abstract class AbstractServer implements ConfigServer {

    /**
     * 服务器配置设置。
     */
    protected final ServerSetting setting;

    /**
     * 过滤器管理器。
     * -- GETTER --
     * 获取指定事件类型的监听器列表，供协议 Server 在合适时机派发。
     * <p>例如 MqttServer 在客户端连接时派发 "open" 事件，
     * WebSocket Server 在会话建立时派发 "open" 事件等。</p>
     *
     * @return 监听器条目列表，可能为 null
     *
     * 过滤器管理器
     */
    @Getter
    protected final ServerFilterManager filterManager;

    /**
     * URL映射过滤器，用于路由匹配。
     */
    protected UrlMappingServerFilter urlMappingFilter;

    /**
     * 服务器运行指标统计。
     *
     * Metrics
     */
    @Getter
    protected final ServerMetrics metrics = new ServerMetrics();

    /**
     * 服务器是否正在运行。
     */
    protected volatile boolean running;

    /**
     * 是否仍在接受新连接。
     *
     * <p>与 {@link #running} 分离：优雅关闭时先摘掉接纳（不再有新连接进来），
     * 再等存量请求跑完，最后才把 {@code running} 置为 false。合并成一个标志会导致
     * 「要么还在接新连接、要么立刻断掉存量」的二选一。</p>
     */
    protected volatile boolean accepting = true;

    /**
     * 对象上下文，用于依赖注入和 Bean 管理。
     */
    protected ObjectContext objectContext;

    /**
     * 端口绑定重试策略。
     *
     * <p>重启时旧进程尚未退出端口仍被占着，直接 bind 会拿到
     * {@code BindException}，节点就此卡在 ERROR。退避重试让这类竞争可自愈，
     * 详见 {@link PortBindRetry}。</p>
     */
    private PortBindRetry.Policy bindRetryPolicy = PortBindRetry.defaultPolicy();

    /**
     * 并发请求闸门。
     *
     * <p>只做「上限判定 + 计数」，绝不排队：超限立即回 503，因此它的语义就是
     * {@code Semaphore.tryAcquire()}，但用 CAS 实现。原因是在百万连接场景下每个请求都要
     * 走一次获取/释放，{@code Semaphore} 的 AQS 会在同一个 state 字段上形成缓存行争用，
     * 且公平性队列、节点对象都是纯负担 —— 我们从不让线程在此等待。</p>
     *
     * <p>值为 {@code null} 表示不限制（{@code maxConcurrency <= 0}）。</p>
     */
    private AtomicInteger concurrencyLimiter;

    /**
     * 构造函数，初始化服务器基础组件。
     *
     * @param setting 服务器配置
     */
    protected AbstractServer(ServerSetting setting) {
        this.setting = setting != null ? setting : ServerSetting.defaults();
        // auto=true 时按当前系统自动调整最优参数（CPU 核数 / 堆内存 / 操作系统）
        if (this.setting.isAuto()) {
            this.setting.autoConfig();
        }
        this.filterManager = new ServerFilterManager(getProtocolType());
        initConcurrencyLimit();

        // 默认创建一个轻量 ObjectContext，使 registerBean() / @AutoInject 等能力开箱即用
        if (this.objectContext == null) {
            this.objectContext = new DefaultObjectContext(ObjectContextConfig.defaults());
        }
        setObjectContext(objectContext);
        initBuiltinFilters();
    }

    /**
     * 初始化内置过滤器。
     *
     * <p>只挂载「无请求级副作用」的基础过滤器(压缩/跨域/安全响应头等)。</p>
     *
     * <p>访问日志与链路追踪不在默认链路上：两者都需要在请求线程上逐请求采集上下文，
     * 即使日志本身异步写出，也会给热路径带来额外开销(实测每请求一行 INFO 会让 echo
     * 吞吐从 10 万级掉到 2 万级)。因此改为显式开启：</p>
     * <ul>
     *   <li>{@code -Dserver.accesslog.enabled=true} — 挂载 {@link AccessLogServerFilter}</li>
     *   <li>{@code -Dserver.tracing.enabled=true} — 挂载 {@link TracingServerFilter}</li>
     * </ul>
     * <p>也可以在启动后通过 {@code server.addFilter(...)} 手动注册。</p>
     */
    private void initBuiltinFilters() {
        addFilter(new GzipFilter());
        if (Boolean.getBoolean("server.accesslog.enabled")) {
            addFilter(new AccessLogServerFilter());
        }
        if (Boolean.getBoolean("server.tracing.enabled")) {
            addFilter(new TracingServerFilter());
        }
        addFilter(new CorsFilter());
        // 安全响应头过滤器始终挂载，但是否真正下发响应头由
        // ServerSetting.SecurityHeadersConfig.enabled 在「请求时」判定。
        // 之所以不在此按 enabled 决定挂载与否：内置过滤器在构造期完成挂载，
        // 而使用方通常在 ServerBuilder.build() 之后才通过 getSetting() 回填配置，
        // 若在构造期判定会导致回填永远不生效。此处传入配置引用，过滤器每次请求实时读取。
        addFilter(new SecurityHeadersServerFilter(setting.getSecurityHeaders()));
        if (urlMappingFilter != null) {
            addFilter(urlMappingFilter);
        }
    }

    /**
     * 初始化并发限制器。
     */
    private void initConcurrencyLimit() {
        int max = setting.getMaxConcurrency();
        // max <= 0 表示不限制：旧实现构造 Semaphore(负数) 会抛 IllegalArgumentException，
        // 而项目内确有 setMaxConcurrency(-1) 的用法（压测基座），此处统一按「不限制」处理。
        this.concurrencyLimiter = max > 0 ? new AtomicInteger() : null;
    }

    /**
     * 尝试占用一个并发名额。
     *
     * @return 未达上限（已占位）返回 true；已达上限返回 false
     */
    private boolean tryAcquireConcurrency() {
        AtomicInteger limiter = this.concurrencyLimiter;
        if (limiter == null) {
            return true;
        }
        int max = setting.getMaxConcurrency();
        while (true) {
            int current = limiter.get();
            if (current >= max) {
                return false;
            }
            if (limiter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 释放一个并发名额。
     *
     * <p>用 CAS 递减而不是「先判断再自减」：并发释放时两个线程可能同时看到
     * {@code 1 > 0} 然后各自自减，把计数打到 -1。计数为负意味着并发上限被
     * 凭空放宽了 N 个名额（这 N 个永远不会补回来）。</p>
     */
    private void releaseConcurrency() {
        AtomicInteger limiter = this.concurrencyLimiter;
        if (limiter == null) {
            return;
        }
        while (true) {
            int current = limiter.get();
            if (current <= 0) {
                return;
            }
            if (limiter.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    /**
     * SupportsReactor
     */
    @Override
    public boolean supportsReactor() {
        return true;
    }

    /**
     * 处理请求的统一入口。
     *
     * @param request  请求对象
     * @param response 响应对象
     */
    protected void handleRequest(ServerRequest request, ServerResponse response) {
        // 同步便捷方法：统一走响应式链并等待完成，保证调用方(JDK/NIO 等 Server 实现)
        // 无需各自等待 stage 也能获得"响应完整后再返回"的语义。
        // 已完成阶段（同步过滤器链 + 同步 handler 的常态）直接跳过 join，避免一次
        // CompletableFuture 阻塞/唤醒；只有真正异步的链才需要等待。
        CompletionStage<Void> stage = handleRequestAsync(request, response);
        if (stage instanceof CompletableFuture<Void> future && future.isDone()) {
            return;
        }
        stage.toCompletableFuture().join();
    }

    /**
     * 处理请求并返回完成信号。
     *
     * <p>供需要等待响应真正写完的协议实现(如 Armeria)使用,
     * 避免异步过滤器链(ReactiveServerFilter)导致响应构建早于处理器完成。</p>
     *
     * @param request  请求对象
     * @param response 响应对象
     * @return 请求处理完成信号
     */
    public CompletionStage<Void> handleRequestWithStage(ServerRequest request, ServerResponse response) {
        return handleRequestAsync(request, response);
    }

    /**
     * 处理请求,返回异步链完成信号(响应式模式下调用方需等待该信号再真正写出响应)。
     *
     * <p>热路径约束（百万连接 / 十万级 RPS 下每一条都是纯开销）：</p>
     * <ul>
     *   <li>不再给每个请求往 attributes 里塞 {@code "_server"} —— 全仓库无任何读取方，
     *       却要为每请求在 {@link java.util.concurrent.ConcurrentHashMap} 里做一次写（该项目内属性表按请求新建，
     *       保留下来只增加 GC 压力）；需要服务器的过滤器可用 {@code ServerFilterConfig#getServerSetting()}
     *       或构造注入；</li>
     *   <li>过滤器链对象保持每请求新建：{@link DefaultReactiveFilterChain} 的游标（{@code index}）
     *       是请求级状态，复用会让并发请求互相踩踏；它只有 3 个字段，分配成本远低于争用成本。
     *       真正的热点在过滤器列表：由 {@link ServerFilterManager#getMergedReactiveFilters()} 按代缓存，
     *       此处不再对其做二次包装；</li>
     *   <li>延迟采样改为可配置（默认每 64 个请求记一次）。{@code System.nanoTime()} 本身不贵，
     *       但「总和/最大值/8 个桶」的原子写在全核满载时是可见的缓存行流量，而监控只需要趋势。</li>
     * </ul>
     *
     * @param request  请求对象
     * @param response 响应对象
     * @return 请求处理完成信号（异步阶段）
     */
    protected CompletionStage<Void> handleRequestAsync(ServerRequest request, ServerResponse response) {
        metrics.incrementRequests();
        if (!tryAcquireConcurrency()) {
            response.setStatus(503).setBody("Service Unavailable: too many concurrent requests");
            if (!response.isEnded()) {
                response.end();
            }
            metrics.incrementErrors();
            return CompletableFuture.completedFuture(null);
        }
        long active = metrics.getActiveRequests() + 1;
        metrics.incrementActive();
        metrics.updatePeakActive(active);
        // 延迟采样：sampleEvery<=1 时全量记录，否则按请求数取模
        boolean sampleLatency = latencySampleEvery <= 1
                || (metrics.getTotalRequests() % latencySampleEvery) == 0;
        long start = sampleLatency ? System.nanoTime() : 0L;
        CompletionStage<Void> stage;
        try {
            stage = handleReactive(request, response);
        } catch (RuntimeException | Error e) {
            // 同步抛出（链在进入前就失败）也必须归还名额，否则名额泄漏会让服务永久 503
            releaseConcurrency();
            metrics.decrementActive();
            throw e;
        }
        return stage.whenComplete((v, ex) -> {
            if (sampleLatency) {
                metrics.recordLatency(System.nanoTime() - start);
            }
            metrics.decrementActive();
            releaseConcurrency();
        });
    }

    /**
     * 延迟采样间隔（每 N 个请求记录一次），1 表示每次请求都记录。
     */
    private volatile int latencySampleEvery = 64;

    /**
     * 设置延迟采样间隔。
     *
     * <p>默认 64：监控看的是分位数趋势，不需要每个请求都写计数器；把它设成 1 可恢复全量记录
     * （排障期用），设成 0 或负数等同 1。</p>
     *
     * @param latencySampleEvery 采样间隔
     */
    public void setLatencySampleEvery(int latencySampleEvery) {
        this.latencySampleEvery = Math.max(latencySampleEvery, 1);
    }

    /**
     * 获取延迟采样间隔。
     *
     * @return 采样间隔
     */
    public int getLatencySampleEvery() {
        return latencySampleEvery;
    }

    /**
     * 处理响应式请求。
     *
     * @param request  请求
     * @param response 响应
     * @return 异步链完成信号,供调用方等待响应真正写完
     */
    protected CompletionStage<Void> handleReactive(ServerRequest request, ServerResponse response) {
        @SuppressWarnings("unchecked")
        List<FilterChainListener> listeners = (List<FilterChainListener>) request.getAttribute("_chainListeners");

        DefaultReactiveFilterChain reactiveChain = new DefaultReactiveFilterChain(
                filterManager.getMergedReactiveFilters(), DEFAULT_404_HANDLER);
        return reactiveChain.doFilter(request, response).whenComplete((result, ex) -> {
            if (ex != null) {
                metrics.incrementErrors();
                log.warn("响应式请求处理异常: {}", ex.getMessage(), ex);
                if (!response.isEnded()) {
                    try {
                        response.sendError(500, "Internal Server Error");
                    } catch (Exception e) {
                        log.warn("发送 500 错误失败: {}", e.getMessage(), e);
                    }
                }
            } else if (!response.isEnded()) {
                // 路径变量 / 查询参数尚未在此处理，交由具体 Handler 负责
                if (response.getResult() != null) {
                    convertResult(response);
                }
                try {
                    response.end();
                } catch (Exception e) {
                    log.warn("响应 end() 失败: {}", e.getMessage(), e);
                }
            }
        });
    }

    /**
     * 默认的 404 处理器。
     */
    private static final ServerHandler DEFAULT_404_HANDLER = (req, res) -> {
        if (!res.isEnded()) {
            res.sendError(404, "Not Found");
        }
    };

    /**
     * 将 response.getResult() 转换为响应体并 end()。
     * <p>优先走 {@link ResponseConverter} SPI，
     * 找不到则直接 toString()。</p>
     *
     * 响应转换器缓存:SPI 列表在运行期稳定,首次加载后缓存,
     * 避免每个请求重复 SPI 扫描 + 排序(高并发热点)。
     */
    private static volatile java.util.List<ResponseConverter> CONVERTER_CACHE;

    /**
     * Converters
     * @return 结果值
     */
    private static java.util.List<ResponseConverter> converters() {
        java.util.List<ResponseConverter> cached = CONVERTER_CACHE;
        if (cached != null) {
            return cached;
        }
        java.util.List<ResponseConverter> loaded = com.chua.common.support.spi.ServiceProvider
                .of(ResponseConverter.class).list().values().stream()
                .sorted(java.util.Comparator.comparingInt(ResponseConverter::getOrder)).toList();
        CONVERTER_CACHE = loaded;
        return loaded;
    }

    /**
     * 转换Result
     * @param response 响应，不允许为 null
     */
    private void convertResult(ServerResponse response) {
        Object result = response.getResult();
        if (result == null) {
            return;
        }
        // SPI 转换器(缓存,避免每请求重复扫描)
        for (var converter : converters()) {
            if (converter.support(result)) {
                try {
                    converter.convert(response, result);
                } catch (Exception e) {
                    log.warn("ResponseConverter 转换失败: {}", e.getMessage(), e);
                }
                return;
            }
        }
        // 兜底：String 直接写，其他 toString()
        if (result instanceof String s) {
            response.setBody(s);
        } else if (result instanceof byte[] b) {
            response.setBody(b);
        } else if (result instanceof java.nio.file.Path p) {
            // zero-copy 发送文件,避免大文件用户态拷贝,提高并发吞吐
            response.sendFile(p);
        } else if (result instanceof java.io.File f) {
            response.sendFile(f.toPath());
        } else {
            response.setBody(result.toString());
        }
    }

    /**
     * 开始
     */
    @Override
    public final synchronized void start() {
        if (running) {
            return;
        }
        logSettingProblems();
        if (objectContext != null) {
            Map<String, ServerFilter> filters = objectContext.getBeanOfTypes(ServerFilter.class);
            if (filters != null) {
                filterManager.addIocFilters(filters);
                for (ServerFilter filter : filters.values()) {
                    if (filter instanceof ReactiveServerFilter reactiveFilter) {
                        filterManager.addReactiveFilter(reactiveFilter);
                    }
                }
            }
        }
        try {
            filterManager.initFilters(new SimpleServerFilterConfig(setting));
            // 先放开接纳，再 bind/启动事件循环：doStart 期间可能已经有连接进来，
            // 若此时 accepting 仍为 false（上一轮 stop 留下的），首个连接会被白白关掉。
            accepting = true;
            // 只把 doStart 放进重试，filterManager.initFilters 留在外面：
            // 重试期间过滤器必须保持已初始化，否则每次重试都会重复 init 一遍，
            // 而 destroyFilters 只应在最终失败时执行一次。
            PortBindRetry.retry(describeEndpoint(), bindRetryPolicy, this::doStart);
            running = true;
            log.info("服务器启动成功：{}://{}:{}", setting.getProtocol(), setting.getHost(), setting.getPort());
        } catch (RuntimeException e) {
            filterManager.destroyFilters();
            throw e;
        }
    }

    /**
     * 启动前把连接/缓冲类配置的可疑组合一次性报出来。
     *
     * <p>百万连接的失败绝大多数是参数不成立（堆不够、fd 不够、backlog 小于突发速率），
     * 这些在启动时就能看出来，比运行期 {@code Too many open files} 好定位。
     * 只告警不阻断：容量目标依赖部署环境，代码无法替部署方做决定。</p>
     */
    private void logSettingProblems() {
        try {
            List<String> problems = setting.validate();
            for (String problem : problems) {
                log.warn("服务器配置风险 [{}:{}]：{}",
                        setting.getProtocol(), setting.getPort(), problem);
            }
        } catch (RuntimeException e) {
            // 校验本身不能成为启动失败的原因
            log.debug("服务器配置校验跳过: {}", e.getMessage());
        }
    }

    /**
     * 取端口绑定重试策略。
     *
     * @return 当前重试策略
     */
    public PortBindRetry.Policy getBindRetryPolicy() {
        return bindRetryPolicy;
    }

    /**
     * 设置端口绑定重试策略，须在 {@link #start()} 之前调用。
     *
     * <p>退出流程比默认 30s 预算更长的部署可以调大；要求端口冲突立即失败、
     * 好让节点尽早显出 ERROR 的场景可以传 {@link PortBindRetry.Policy#noRetry()}。</p>
     *
     * @param bindRetryPolicy 重试策略，{@code null} 表示恢复默认
     */
    public void setBindRetryPolicy(PortBindRetry.Policy bindRetryPolicy) {
        this.bindRetryPolicy = bindRetryPolicy == null ? PortBindRetry.defaultPolicy() : bindRetryPolicy;
    }

    /**
     * 端点描述，进重试日志与异常信息。
     *
     * <p>把协议与端口写进去，是因为这类失败的排查第一步就是「到底是谁的哪个端口」，
     * 只报一句 Address already in use 会让人先去找错进程。</p>
     *
     * @return 端点描述
     */
    private String describeEndpoint() {
        return "服务器 " + setting.getProtocol() + "://" + setting.getHost() + ":" + setting.getPort();
    }

    /**
     * 启动服务器的逻辑。
     * <p>默认实现为空，由具体实现类完成启动逻辑。</p>
     */
    protected abstract void doStart();

    /**
     * 停止
     */
    @Override
    public final synchronized void stop() {
        if (!running) {
            return;
        }
        // 先摘接纳：存量请求还能跑完，新连接不再进来。
        // 若直接置 running=false，各事件循环会立刻退出，正在处理的请求被腰斩。
        accepting = false;
        doStopAccepting();
        int quietPeriod = setting.getShutdownQuietPeriod();
        if (quietPeriod > 0) {
            log.info("服务器开始优雅关闭，等待活跃请求完成（最长 {}s）...", quietPeriod);
            long deadline = System.currentTimeMillis() + quietPeriod * 1000L;
            long active = metrics.getActiveRequests();
            while (active > 0 && System.currentTimeMillis() < deadline) {
                ThreadUtils.sleep(50);
                active = metrics.getActiveRequests();
            }
            if (active > 0) {
                log.warn("优雅关闭超时，尚有 {} 个请求未完成", active);
            } else {
                log.info("所有活跃请求已处理完成");
            }
        }
        running = false;
        filterManager.destroyFilters();
        doStop();
        metrics.resetConnections();
        log.info("服务器已停止：{}://{}:{}", setting.getProtocol(), setting.getHost(), setting.getPort());
    }

    /**
     * 停止服务器的逻辑。
     * <p>默认实现为空，由具体实现类完成停止逻辑。</p>
     */
    protected abstract void doStop();

    /**
     * 停止接收新的请求。
     * <p>默认实现为空，传输层可在优雅关闭等待开始前关闭监听端口，
     * 避免等待活跃请求期间继续接收新请求。</p>
     */
    protected void doStopAccepting() {
    }

    /**
     * 是否Running
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取指标
     */
    @Override
    public ServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * 是否仍接受新连接
     */
    @Override
    public boolean isAcceptingConnections() {
        return accepting && running;
    }

    /**
     * 开关连接接纳
     */
    @Override
    public void setAccepting(boolean accepting) {
        this.accepting = accepting;
        if (!accepting) {
            doStopAccepting();
        } else if (running) {
            doStartAccepting();
        }
    }

    /**
     * 恢复接受新连接。
     *
     * <p>与 {@link #doStopAccepting()} 对称，供 {@link #setAccepting(boolean)} 在「摘流后又放开」
     * 的场景使用（如灰度回滚）。默认实现为空：多数实现关闭监听通道后无法原地重开，
     * 需要先 stop 再 start。</p>
     */
    protected void doStartAccepting() {
    }

    /**
     * 判断是否已达连接上限（未配置上限时恒为 false）。
     *
     * <p>供传输层在 accept 之后、注册连接之前判定：只在超限时才走拒绝路径，
     * 热路径上就是一次 {@link AtomicInteger} 读，没有锁也没有队列。</p>
     *
     * @return true 表示应当拒绝这个新连接
     */
    protected boolean isConnectionLimitReached() {
        int limit = setting.getMaxConnections();
        return limit > 0 && metrics.getActiveConnections() >= limit;
    }

    /**
     * 登记一个新连接（含上限判定与峰值刷新）。
     *
     * <p>与 {@link #isConnectionLimitReached()} 相比，本方法用一次 CAS 完成「判上限 + 自增」，
     * 是并发 accept 下的正确做法；传输层应优先使用本方法。</p>
     *
     * @return true 表示登记成功（调用方需在连接关闭时调用 {@link #releaseConnection()}）；
     *         false 表示已达上限，调用方应关闭该连接
     */
    protected boolean tryAcquireConnection() {
        return metrics.tryOpenConnection(setting.getMaxConnections());
    }

    /**
     * 释放一个连接名额（连接关闭时调用）。
     *
     * <p>必须与 {@link #tryAcquireConnection()} 严格配对，且只在其返回 true 时调用；
     * 重复释放会把活跃数打到负数，让「当前连接数」这项监控失去意义。</p>
     */
    protected void releaseConnection() {
        metrics.closeConnection();
    }

    /**
     * 给刚接入的 {@link NetworkChannel} 应用内核缓冲区配置。
     *
     * <p>只处理 {@link ServerSetting#getSoSndBuf()} / {@link ServerSetting#getSoRcvBuf()} 显式
     * 配置（{@code >0}）的部分，两者都为 0（默认）时直接返回，热路径上只有两次字段读，
     * 不产生任何系统调用：百万连接场景下「每连接放大缓冲」等于把内核非分页内存按连接数倍乘，
     * 只有在明确知道带宽/延迟特性时手动配置才有意义。</p>
     *
     * <p>设置失败（平台不支持或超权限）只降级告警，不影响连接继续服务。</p>
     *
     * @param channel 已接受的通道，可为 null
     */
    protected void applyChannelSocketOptions(NetworkChannel channel) {
        if (channel == null) {
            return;
        }
        int sndBuf = setting.getSoSndBuf();
        int rcvBuf = setting.getSoRcvBuf();
        if (sndBuf <= 0 && rcvBuf <= 0) {
            return;
        }
        try {
            if (sndBuf > 0) {
                channel.setOption(StandardSocketOptions.SO_SNDBUF, sndBuf);
            }
            if (rcvBuf > 0) {
                channel.setOption(StandardSocketOptions.SO_RCVBUF, rcvBuf);
            }
        } catch (Exception e) {
            log.debug("设置连接缓冲区失败（soSndBuf={}, soRcvBuf={}）: {}",
                    sndBuf, rcvBuf, e.getMessage());
        }
    }

    /**
     * 给刚接入的阻塞式 {@link Socket} 应用内核缓冲区配置。
     *
     * <p>与 {@link #applyChannelSocketOptions(NetworkChannel)} 同一口径：未显式配置时不做任何
     * 调用，避免给每条新连接额外两次系统调用。</p>
     *
     * @param socket 已接受的套接字，可为 null
     */
    protected void applySocketOptions(Socket socket) {
        if (socket == null) {
            return;
        }
        int sndBuf = setting.getSoSndBuf();
        int rcvBuf = setting.getSoRcvBuf();
        if (sndBuf <= 0 && rcvBuf <= 0) {
            return;
        }
        try {
            if (sndBuf > 0) {
                socket.setSendBufferSize(sndBuf);
            }
            if (rcvBuf > 0) {
                socket.setReceiveBufferSize(rcvBuf);
            }
        } catch (Exception e) {
            log.debug("设置连接缓冲区失败（soSndBuf={}, soRcvBuf={}）: {}",
                    sndBuf, rcvBuf, e.getMessage());
        }
    }

    /**
     * 构造超限时的拒绝响应。
     *
     * <p>对 HTTP 回 503 + {@code Retry-After}（让上游负载均衡正确摘流而不是当成网络故障）；
     * 非 HTTP 或配置关闭时返回 null，调用方直接关闭连接。</p>
     *
     * @param response 响应对象，可为 null
     * @return 已完成的拒绝响应，或 null 表示应直接断开
     */
    protected boolean rejectOverloadedConnection(ServerResponse response) {
        if (response == null || !setting.isRejectWith503()) {
            return false;
        }
        if (response.isEnded()) {
            return true;
        }
        response.setStatus(503);
        if (response.getHeader("Retry-After") == null) {
            response.setHeader("Retry-After", "1");
        }
        response.setBody("Service Unavailable: connection limit reached (maxConnections="
                + setting.getMaxConnections() + ")");
        response.end();
        return true;
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * 获取Port
     */
    @Override
    public int getPort() {
        return setting.getPort();
    }

    /**
     * 获取Setting
     */
    @Override
    public ServerSetting getSetting() {
        return setting;
    }

    /**
     * 获取ObjectContext
     */
    @Override
    public ObjectContext getObjectContext() {
        return objectContext;
    }

    /**
     * 设置ObjectContext
     */
    @Override
    public void setObjectContext(ObjectContext objectContext) {
        this.objectContext = objectContext;
        this.urlMappingFilter = new UrlMappingServerFilter(objectContext);
        if (objectContext != null) {
            Map<String, ServerFilter> filters = objectContext.getBeanOfTypes(ServerFilter.class);
            if (filters != null) {
                filterManager.addIocFilters(filters);
                for (ServerFilter filter : filters.values()) {
                    if (filter instanceof ReactiveServerFilter reactiveFilter) {
                        filterManager.addReactiveFilter(reactiveFilter);
                    }
                }
            }
        }
    }

    /**
     * 获取Filters
     */
    @Override
    public List<ServerFilter> getFilters() {
        return filterManager.getStaticFilters();
    }

    /**
     * 添加过滤
     */
    @Override
    public Server addFilter(ServerFilter filter) {
        filterManager.addFilter(filter);
        if (filter instanceof ReactiveServerFilter reactiveFilter) {
            filterManager.addReactiveFilter(reactiveFilter);
        }
        return this;
    }

    /**
     * 移除过滤
     *
     * <p>必须与 {@link #addFilter} 对称：既摘阻塞列表，也摘响应式列表。原先只摘阻塞列表，
     * 而 JDK/响应式服务器走的是 {@code getMergedReactiveFilters()} 链，于是同时实现
     * {@code ReactiveServerFilter} 的插件（如 {@code FileStorageServerFilter}，其逻辑全在
     * 响应式 {@code doFilter} 里）热更新后旧实例仍留在链上并先终结响应，新实例永远不可达，
     * 表现为「参数已落库、实例也重建了，运行中行为却不变，只有停→起才生效」。</p>
     */
    @Override
    public Server removeFilter(ServerFilter filter) {
        filterManager.removeFilter(filter);
        if (filter instanceof ReactiveServerFilter reactiveFilter) {
            filterManager.removeReactiveFilter(reactiveFilter);
        }
        return this;
    }

    /**
     * 注册Mapping
     */
    @Override
    public Server registerMapping(String path, HttpMethod method, ServerHandler handler) {
        urlMappingFilter.route(path, method, handler);
        return this;
    }

    /**
     * 注册Mapping
     */
    @Override
    public Server registerMapping(String path, ServerHandler handler) {
        urlMappingFilter.route(path, handler);
        return this;
    }

    /**
     * 移除Mapping
     */
    @Override
    public Server removeMapping(String path) {
        urlMappingFilter.removeRoute(path);
        return this;
    }

    /**
     * RefreshFilters
     */
    @Override
    public Server refreshFilters() {
        filterManager.refreshSpiFilters();
        if (objectContext != null) {
            Map<String, ServerFilter> filters = objectContext.getBeanOfTypes(ServerFilter.class);
            if (filters != null) {
                filterManager.addIocFilters(filters);
            }
        }
        return this;
    }

    /**
     * 注册Bean
     */
    @Override
    public Server registerBean(Object bean) {
        if (bean == null) {
            return this;
        }
        objectContext.registerBean(bean);
        return this;
    }

    /**
     * 通过 SPI 发现 {@link HandlerMethodArgumentResolver} 实现并注册到 {@link ObjectContext}，
     * 同时将 {@link UrlMappingServerFilter} 注入容器。
     */

    @Override
    public Server unregisterBean(Object bean) {
        if (bean == null || objectContext == null) {
            return this;
        }
        objectContext.unregisterBean(bean);
        return this;
    }

    /**
     * 简单的 ServerFilterConfig 实现，用于内置过滤器。
     */
    private static class SimpleServerFilterConfig implements ServerFilterConfig {
        /**
         * 设置
         */
        private final ServerSetting setting;

        /**
         * Simple Server Filter Config
         * @param setting 参数
         * @return 返回值
        */
        SimpleServerFilterConfig(ServerSetting setting) {
            this.setting = setting;
        }

        /**
         * 获取过滤Name
         */
        @Override
        public String getFilterName() {
            return "default";
        }

        /**
         * 获取初始化Parameter
         */
        @Override
        public String getInitParameter(String name) {
            return null;
        }

        /**
         * 获取初始化Parameters
         */
        @Override
        public Map<String, String> getInitParameters() {
            return Map.of();
        }

        /**
         * 获取ServerSetting
         */
        @Override
        public ServerSetting getServerSetting() {
            return setting;
        }
    }
}
