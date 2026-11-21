// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.filter.rate.RateLimitProvider;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通道排队过滤器（响应式）：把并发请求分摊到 N 个"管道"（通道）上，
 * 每个通道独立限流 + 独立有界等待队列，超出的请求非阻塞排队或快速失败。
 *
 * <h2>能力</h2>
 * <ul>
 *   <li><b>管道数（并发通道）</b>：{@code channels}，决定同一时刻最多放行多少个请求。</li>
 *   <li><b>每通道限流</b>：{@code channelQps}，每个通道一秒最多放行多少请求（令牌桶）。</li>
 *   <li><b>auto 自动计算</b>：两者均可传 {@link #AUTO}，按服务器 CPU 核数推算——
 *       通道数 = max(2, 核数-1)，每通道 QPS = max(100, 核数×100)。</li>
 *   <li><b>非阻塞排队</b>：拿到令牌前请求进入该通道的等待队列（容量 {@code queueCapacity}），
 *       由共享守护调度器按 {@code retryIntervalMs} 节拍探测令牌，<b>不占用任何工作线程</b>。</li>
 *   <li><b>快速失败</b>：等待队列满 → 429；排队超过 {@code queueTimeoutMs} → 503，
 *       均携带 {@code Retry-After} 与 {@code X-Channel-Queue} 头。</li>
 * </ul>
 *
 * <h2>整体吞吐</h2>
 * <p>稳态总吞吐 ≈ {@code channels × channelQps}。突发流量先消耗各通道令牌桶存量，
 * 再进入等待队列削峰，超出容量的部分立即被拒绝。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 方式一：构造后注册到响应式过滤器链（只对 AIO 等响应式服务器链生效）
 * AbstractServer server = (AbstractServer) Server.builder()
 *         .protocol(ProtocolType.AIO)
 *         .port(8080)
 *         .build();
 * // 8 个通道，每通道每秒 500 次；也支持 new ChannelQueueServerFilter() 全 auto
 * server.addReactiveFilter(new ChannelQueueServerFilter(8, 500));
 * server.start();
 *
 * // 方式二：系统属性配置（无参构造时读取，全部支持 auto）
 * // -Dserver.filter.channel-queue.channels=auto
 * // -Dserver.filter.channel-queue.channel-qps=500
 * // -Dserver.filter.channel-queue.queue-capacity=1024
 * // -Dserver.filter.channel-queue.queue-timeout-ms=30000
 * // -Dserver.filter.channel-queue.retry-interval-ms=25
 * // -Dserver.filter.channel-queue.rate-limit-provider=guava
 * }</pre>
 *
 * <h2>注意事项</h2>
 * <ul>
 *   <li>本过滤器<b>只实现 {@link ReactiveServerFilter}</b>：响应式链里它以原始实例参与
 *       （不会被静态包装面遮蔽），排队全程不阻塞 IO 线程。它不进入同步过滤器链，
 *       NIO 同步链不会执行它；也不注册 SPI，不会被动注入任何宿主。</li>
 *   <li>注册请使用 {@code AbstractServer#addReactiveFilter}（或 IOC 收集后由宿主自行注册）。</li>
 *   <li>限流算法复用 {@link RateLimitProvider} SPI（默认 {@code guava} 令牌桶），不重复造轮子。</li>
 *   <li>调度器为全 JVM 共享的守护线程池，服务器关闭后线程自动退出；本过滤器实现
 *       {@link AutoCloseable}，可在停机时调用 {@link #close()} 立即清空等待队列。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ChannelQueueServerFilter implements ReactiveServerFilter, AutoCloseable {

    /**
     * auto 配置值：按服务器 CPU 核数自动计算
     */
    public static final String AUTO = "auto";

    /**
     * 默认每通道等待队列容量
     */
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;

    /**
     * 默认排队超时（毫秒）
     */
    private static final long DEFAULT_QUEUE_TIMEOUT_MS = 30_000L;

    /**
     * 默认令牌探测间隔（毫秒）
     */
    private static final long DEFAULT_RETRY_INTERVAL_MS = 25L;

    /**
     * 默认限流提供者（SPI 名称）
     */
    private static final String DEFAULT_RATE_LIMIT_PROVIDER = "guava";

    /**
     * auto 通道数下限
     */
    private static final int MIN_AUTO_CHANNELS = 2;

    /**
     * auto 每通道 QPS 的每核配额
     */
    private static final int AUTO_QPS_PER_CORE = 100;

    /**
     * auto 每通道 QPS 下限
     */
    private static final int MIN_AUTO_QPS = 100;

    /**
     * 系统属性前缀
     */
    private static final String PROP_PREFIX = "server.filter.channel-queue.";

    /**
     * 429 Too Many Requests：等待队列已满
     */
    private static final int STATUS_QUEUE_FULL = 429;

    /**
     * 503 Service Unavailable：排队超时或停机中
     */
    private static final int STATUS_UNAVAILABLE = 503;

    /**
     * Retry-After 响应头
     */
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    /**
     * 排队状态响应头
     */
    private static final String HEADER_QUEUE_STATE = "X-Channel-Queue";

    /**
     * 执行顺序：排在 RateLimitServerFilter（MIN_VALUE+30）之前，先做通道准入再做通用限流。
     *
     * <p>本过滤器是纯响应式过滤器，必须经 {@code Server#addReactiveFilter} 注册；
     * 排队时返回未完成 stage，由服务端等待排队结果。同步过滤器的响应式包装面
     * 会把下游 stage 原样带回，因此本过滤器无需强占链首位置。</p>
     */
    private static final int ORDER = Integer.MIN_VALUE + 20;

    /**
     * 调度线程序号
     */
    private static final AtomicInteger SCHEDULER_SEQ = new AtomicInteger();

    /**
     * 全 JVM 共享的令牌探测调度器：守护线程，探测任务是纯内存 O(1) 操作，
     * 线程数取 min(4, 核数/2)，避免多 server 实例重复创建线程池。
     */
    private static final ScheduledExecutorService SCHEDULER = new ScheduledThreadPoolExecutor(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            task -> {
                Thread thread = new Thread(task, "channel-queue-filter-" + SCHEDULER_SEQ.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 通道数
     */
    private final int channels;

    /**
     * 每通道每秒放行数
     */
    private final double channelQps;

    /**
     * 每通道等待队列容量
     */
    private final int queueCapacity;

    /**
     * 排队超时（毫秒）
     */
    private final long queueTimeoutMs;

    /**
     * 令牌探测间隔（毫秒）
     */
    private final long retryIntervalMs;

    /**
     * 限流提供者 SPI 名称
     */
    private final String rateLimitProviderName;

    /**
     * 通道数组（构建后不可变）
     */
    private final Channel[] channelArray;

    /**
     * 启用开关：关闭后所有请求直接放行（本过滤器不实现 ServerFilter.isEnabled，
     * SPI/热启停机制不感知它，开关由持有方显式控制）
     */
    private volatile boolean enabled = true;

    /**
     * 关闭标记：关闭后新请求直接放行，等待中的请求被清空拒绝
     */
    private volatile boolean closed = false;

    /**
     * 轮询计数器：请求按序分散到各通道
     */
    private final AtomicLong counter = new AtomicLong();

    /**
     * 累计放行数（含从队列放行）
     */
    private final AtomicLong acceptedTotal = new AtomicLong();

    /**
     * 累计拒绝数（队列满 + 排队超时 + 停机清空）
     */
    private final AtomicLong rejectedTotal = new AtomicLong();

    /**
     * 当前排队中的请求数
     */
    private final AtomicLong queuedNow = new AtomicLong();

    /**
     * 全 auto 构造：通道数与每通道 QPS 按 CPU 核数计算，
     * 其余参数取默认值；系统属性 {@code server.filter.channel-queue.*} 可覆盖。
     */
    public ChannelQueueServerFilter() {
        this(resolveChannelsSystemProperty(),
                resolveQpsSystemProperty(),
                (int) resolveLongSystemProperty("queue-capacity", DEFAULT_QUEUE_CAPACITY),
                resolveLongSystemProperty("queue-timeout-ms", DEFAULT_QUEUE_TIMEOUT_MS),
                resolveLongSystemProperty("retry-interval-ms", DEFAULT_RETRY_INTERVAL_MS),
                System.getProperty(PROP_PREFIX + "rate-limit-provider", DEFAULT_RATE_LIMIT_PROVIDER));
    }

    /**
     * 指定通道数与每通道 QPS 构造，其余参数取默认值。
     *
     * @param channels   管道数（并发通道），&gt;=1；传 {@code -1} 表示按核数 auto
     * @param channelQps 每通道每秒放行数，&gt;0；传 {@code -1} 表示按核数 auto
     */
    public ChannelQueueServerFilter(int channels, double channelQps) {
        this(channels, channelQps, DEFAULT_QUEUE_CAPACITY, DEFAULT_QUEUE_TIMEOUT_MS, DEFAULT_RETRY_INTERVAL_MS,
                DEFAULT_RATE_LIMIT_PROVIDER);
    }

    /**
     * 全参数构造。
     *
     * @param channels      管道数（并发通道），&gt;=1；传 {@code -1} 表示按核数 auto
     * @param channelQps    每通道每秒放行数，&gt;0；传 {@code -1} 表示按核数 auto
     * @param queueCapacity 每通道等待队列容量，&gt;=0（0 表示不排队，超出立即拒绝）
     * @param queueTimeoutMs 排队超时毫秒数，&gt;0
     */
    public ChannelQueueServerFilter(int channels, double channelQps, int queueCapacity, long queueTimeoutMs) {
        this(channels, channelQps, queueCapacity, queueTimeoutMs, DEFAULT_RETRY_INTERVAL_MS,
                DEFAULT_RATE_LIMIT_PROVIDER);
    }

    /**
     * 内部全参构造。
     *
     * @param channels              管道数
     * @param channelQps            每通道 QPS
     * @param queueCapacity         每通道等待队列容量
     * @param queueTimeoutMs        排队超时毫秒数
     * @param retryIntervalMs       令牌探测间隔毫秒数
     * @param rateLimitProviderName 限流提供者 SPI 名称
     */
    private ChannelQueueServerFilter(int channels, double channelQps, int queueCapacity, long queueTimeoutMs,
                                     long retryIntervalMs, String rateLimitProviderName) {
        int resolvedChannels = channels <= 0 ? autoChannels() : channels;
        double resolvedQps = channelQps <= 0 ? autoChannelQps() : channelQps;
        if (resolvedChannels < 1) {
            throw new IllegalArgumentException("channels 必须 >= 1: " + resolvedChannels);
        }
        if (resolvedQps <= 0) {
            throw new IllegalArgumentException("channelQps 必须 > 0: " + resolvedQps);
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("queueCapacity 必须 >= 0: " + queueCapacity);
        }
        if (queueTimeoutMs <= 0 || retryIntervalMs <= 0) {
            throw new IllegalArgumentException("queueTimeoutMs/retryIntervalMs 必须 > 0");
        }
        this.channels = resolvedChannels;
        this.channelQps = resolvedQps;
        this.queueCapacity = queueCapacity;
        this.queueTimeoutMs = queueTimeoutMs;
        this.retryIntervalMs = retryIntervalMs;
        this.rateLimitProviderName = rateLimitProviderName;
        this.channelArray = buildChannels(resolvedChannels, resolvedQps, queueCapacity, rateLimitProviderName);
        log.info("ChannelQueueServerFilter 初始化: channels={}, channelQps={}, queueCapacity={}, "
                        + "queueTimeoutMs={}, retryIntervalMs={}, rateLimitProvider={}",
                resolvedChannels, resolvedQps, queueCapacity, queueTimeoutMs, retryIntervalMs, rateLimitProviderName);
    }

    /**
     * 构建通道数组。
     *
     * @param channelCount  通道数
     * @param qps           每通道 QPS
     * @param capacity      等待队列容量
     * @param providerName  限流提供者名称
     * @return 通道数组
     */
    private static Channel[] buildChannels(int channelCount, double qps, int capacity, String providerName) {
        RateLimitProvider provider = ServiceProvider.of(RateLimitProvider.class).getExtension(providerName);
        RateLimitProvider.RateLimiter limiter = null;
        if (provider == null) {
            log.warn("未找到 RateLimitProvider: {}，通道排队过滤器将只排队不限流", providerName);
        } else {
            limiter = provider.create(qps);
        }
        Channel[] result = new Channel[channelCount];
        for (int i = 0; i < channelCount; i++) {
            result[i] = new Channel(i, limiter, capacity);
        }
        return result;
    }

    /**
     * 按服务器资源自动计算通道数：max(2, 核数-1)。
     *
     * <p>通道本质是"并发放行预算"，与 CPU 核数同量级可避免过载；
     * 预留一核给业务线程与系统，下限 2 保证小机器也有基本并发。</p>
     *
     * @return 自动通道数
     */
    public static int autoChannels() {
        return Math.max(MIN_AUTO_CHANNELS, Runtime.getRuntime().availableProcessors() - 1);
    }

    /**
     * 按服务器资源自动计算每通道 QPS：max(100, 核数×100)。
     *
     * <p>每核每秒 100 个请求是保守估计（单请求按 10ms CPU 时间折算），
     * 可通过显式配置覆盖；总吞吐 = 通道数 × 每通道 QPS。</p>
     *
     * @return 自动每通道 QPS
     */
    public static double autoChannelQps() {
        return Math.max(MIN_AUTO_QPS, (double) Runtime.getRuntime().availableProcessors() * AUTO_QPS_PER_CORE);
    }

    /**
     * 从系统属性解析通道数（支持 auto）。
     *
     * @return 通道数；未配置返回 -1（交由构造器按 auto 处理）
     */
    private static int resolveChannelsSystemProperty() {
        String value = System.getProperty(PROP_PREFIX + "channels");
        if (value == null || value.isBlank() || AUTO.equalsIgnoreCase(value.trim())) {
            return -1;
        }
        return Integer.parseInt(value.trim());
    }

    /**
     * 从系统属性解析每通道 QPS（支持 auto）。
     *
     * @return 每通道 QPS；未配置返回 -1（交由构造器按 auto 处理）
     */
    private static double resolveQpsSystemProperty() {
        String value = System.getProperty(PROP_PREFIX + "channel-qps");
        if (value == null || value.isBlank() || AUTO.equalsIgnoreCase(value.trim())) {
            return -1;
        }
        return Double.parseDouble(value.trim());
    }

    /**
     * 从系统属性解析 long 型配置。
     *
     * @param name         属性名（不含前缀）
     * @param defaultValue 默认值
     * @return 配置值
     */
    private static long resolveLongSystemProperty(String name, long defaultValue) {
        String value = System.getProperty(PROP_PREFIX + name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value.trim());
    }

    /**
     * 响应式过滤入口：拿到令牌直接放行，拿不到则非阻塞排队。
     */
    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response, ReactiveFilterChain chain) {
        if (!enabled || closed) {
            return chain.doFilter(request, response);
        }
        // 轮询选通道：floorMod 防御极端计数下溢
        Channel[] current = this.channelArray;
        Channel channel = current[Math.floorMod(counter.incrementAndGet(), current.length)];
        if (channel.limiter == null || channel.limiter.tryAcquire(channel.key)) {
            acceptedTotal.incrementAndGet();
            return dispatch(channel, request, response, chain);
        }
        return enqueue(channel, request, response, chain);
    }

    /**
     * 直接放行：记录通道活跃数，链完成后归还。
     *
     * @param channel  命中的通道
     * @param request  请求
     * @param response 响应
     * @param chain    过滤器链
     * @return 链执行阶段
     */
    private CompletionStage<Void> dispatch(Channel channel, ServerRequest request, ServerResponse response,
                                           ReactiveFilterChain chain) {
        channel.active.incrementAndGet();
        try {
            return chain.doFilter(request, response)
                    .whenComplete((unused, error) -> channel.active.decrementAndGet());
        } catch (Throwable throwable) {
            channel.active.decrementAndGet();
            var failed = new CompletableFuture<Void>();
            failed.completeExceptionally(throwable);
            return failed;
        }
    }

    /**
     * 非阻塞排队：占一个等待槽位后入队，由调度器探测令牌放行。
     *
     * @param channel  目标通道
     * @param request  请求
     * @param response 响应
     * @param chain    过滤器链
     * @return 排队并执行完成的阶段
     */
    private CompletionStage<Void> enqueue(Channel channel, ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        if (!channel.capacity.tryAcquire()) {
            rejectedTotal.incrementAndGet();
            reject(response, STATUS_QUEUE_FULL, "queue_full", "服务通道排队已满，请稍后重试");
            return CompletableFuture.completedFuture(null);
        }
        PendingRequest pending = new PendingRequest(request, response, chain,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(queueTimeoutMs));
        channel.waiters.add(pending);
        queuedNow.incrementAndGet();
        scheduleDrain(channel);
        return pending.future;
    }

    /**
     * 调度一次令牌探测：同通道同时最多一个探测任务在飞，避免重试风暴。
     *
     * <p>令牌间隔明显大于轮询粒度时（低 QPS 通道）按限流器预估值精确对齐下一次放行，
     * 消除固定轮询平均半个周期的额外排队延迟；令牌间隔较短时维持固定轮询——
     * 短间隔下「调度触发到任务执行」的微秒级滞后会让精确唤醒周期性早醒空探、
     * 反复多等一个整周期，而固定轮询可凭令牌桶突发存量拿满吞吐。</p>
     *
     * @param channel 目标通道
     */
    private void scheduleDrain(Channel channel) {
        if (channel.drainScheduled.compareAndSet(false, true)) {
            long delay = retryIntervalMs;
            if (channel.limiter != null) {
                long estimated = channel.limiter.estimateNextAvailableMillis(channel.key);
                // 仅当预估间隔大于轮询粒度时启用精确对齐；+1ms 覆盖调度滞后防早醒空探，
                // 上限钳到 queueTimeoutMs，保证排队超时拒绝仍能按期被 drain 探测到
                if (estimated > retryIntervalMs) {
                    delay = Math.min(estimated + 1, queueTimeoutMs);
                }
            }
            SCHEDULER.schedule(() -> drain(channel), delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 探测通道队头：过期则拒绝，拿到令牌则放行，无令牌则等待下一拍。
     *
     * <p>只有队头能拿令牌，保证先来先服务；一轮连续放行直到令牌耗尽或队列清空
     * （令牌桶存量允许突发）。</p>
     *
     * @param channel 目标通道
     */
    private void drain(Channel channel) {
        channel.drainScheduled.set(false);
        if (closed) {
            failAll(channel);
            return;
        }
        while (true) {
            PendingRequest head = channel.waiters.peek();
            if (head == null) {
                break;
            }
            if (head.isExpired()) {
                if (channel.waiters.poll() != head) {
                    continue;
                }
                channel.capacity.release();
                queuedNow.decrementAndGet();
                rejectedTotal.incrementAndGet();
                reject(head.response, STATUS_UNAVAILABLE, "queue_timeout", "服务通道排队超时，请稍后重试");
                head.future.complete(null);
                continue;
            }
            if (channel.limiter != null && !channel.limiter.tryAcquire(channel.key)) {
                break;
            }
            if (channel.waiters.poll() != head) {
                continue;
            }
            channel.capacity.release();
            queuedNow.decrementAndGet();
            acceptedTotal.incrementAndGet();
            dispatchPending(head);
        }
        if (!channel.waiters.isEmpty()) {
            scheduleDrain(channel);
        }
    }

    /**
     * 执行出队请求的下游链，并把结果回填到排队的 future。
     *
     * @param pending 出队请求
     */
    private void dispatchPending(PendingRequest pending) {
        try {
            pending.chain.doFilter(pending.request, pending.response).whenComplete((unused, error) -> {
                if (error != null) {
                    pending.future.completeExceptionally(error);
                } else {
                    pending.future.complete(null);
                }
            });
        } catch (Throwable throwable) {
            pending.future.completeExceptionally(throwable);
        }
    }

    /**
     * 直接拒绝并结束响应。
     *
     * @param response 响应
     * @param status   HTTP 状态码
     * @param state    排队状态标识（写入 X-Channel-Queue）
     * @param message  提示信息
     */
    private void reject(ServerResponse response, int status, String state, String message) {
        try {
            response.setStatus(status);
            response.setHeader(HEADER_RETRY_AFTER, "1");
            response.setHeader(HEADER_QUEUE_STATE, state);
            response.setBody("{\"error\":\"" + state + "\",\"message\":\"" + message + "\"}");
        } catch (Exception exception) {
            log.warn("通道排队拒绝响应写出失败: {}", exception.getMessage());
        } finally {
            try {
                response.end();
            } catch (Exception exception) {
                log.debug("通道排队拒绝响应结束失败: {}", exception.getMessage());
            }
        }
    }

    /**
     * 清空通道等待队列（停机/关闭时调用）。
     *
     * @param channel 目标通道
     */
    private void failAll(Channel channel) {
        PendingRequest pending;
        while ((pending = channel.waiters.poll()) != null) {
            channel.capacity.release();
            queuedNow.decrementAndGet();
            rejectedTotal.incrementAndGet();
            reject(pending.response, STATUS_UNAVAILABLE, "server_closed", "服务器正在关闭，请求未被处理");
            pending.future.complete(null);
        }
    }

    /**
     * 关闭过滤器：停止接纳新排队请求，清空全部等待中的请求。
     */
    @Override
    public void close() {
        closed = true;
        for (Channel channel : channelArray) {
            failAll(channel);
        }
    }

    /**
     * 设置启用开关。
     *
     * @param enabled true 启用，false 直通放行
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 是否启用。
     *
     * @return true 启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取通道数。
     *
     * @return 通道数
     */
    public int getChannels() {
        return channels;
    }

    /**
     * 获取每通道 QPS。
     *
     * @return 每通道每秒放行数
     */
    public double getChannelQps() {
        return channelQps;
    }

    /**
     * 获取每通道等待队列容量。
     *
     * @return 队列容量
     */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * 当前正在执行中的请求数（所有通道合计）。
     *
     * @return 活跃请求数
     */
    public long getActiveRequests() {
        long total = 0;
        for (Channel channel : channelArray) {
            total += channel.active.get();
        }
        return total;
    }

    /**
     * 当前排队中的请求数（所有通道合计）。
     *
     * @return 排队请求数
     */
    public long getQueuedRequests() {
        return queuedNow.get();
    }

    /**
     * 累计放行请求数。
     *
     * @return 放行总数
     */
    public long getAcceptedTotal() {
        return acceptedTotal.get();
    }

    /**
     * 累计拒绝请求数（队列满 + 排队超时 + 停机清空）。
     *
     * @return 拒绝总数
     */
    public long getRejectedTotal() {
        return rejectedTotal.get();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String toString() {
        return "ChannelQueueServerFilter{channels=" + channels + ", channelQps=" + channelQps
                + ", queueCapacity=" + queueCapacity + ", queueTimeoutMs=" + queueTimeoutMs
                + ", retryIntervalMs=" + retryIntervalMs + ", provider=" + rateLimitProviderName + "}";
    }

    /**
     * 通道：独立令牌桶 + 独立有界等待队列。
     */
    private static final class Channel {

        /**
         * 通道序号
         */
        private final int index;

        /**
         * 限流 key（透传给 RateLimiter，guava 全局实现忽略该值）
         */
        private final String key;

        /**
         * 每通道令牌桶限流器（provider 缺失时为 null，表示不限流）
         */
        private final RateLimitProvider.RateLimiter limiter;

        /**
         * 等待队列容量信号量
         */
        private final Semaphore capacity;

        /**
         * 等待队列（仅队头被 drain 消费，保证先来先服务）
         */
        private final ConcurrentLinkedQueue<PendingRequest> waiters = new ConcurrentLinkedQueue<>();

        /**
         * 探测任务单飞标记：同通道同时最多一个探测任务在飞
         */
        private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

        /**
         * 当前执行中的请求数
         */
        private final AtomicLong active = new AtomicLong();

        /**
         * 创建通道。
         *
         * @param index     通道序号
         * @param limiter   限流器（可为 null）
         * @param capacity  等待队列容量
         */
        Channel(int index, RateLimitProvider.RateLimiter limiter, int capacity) {
            this.index = index;
            this.key = "channel-" + index;
            this.limiter = limiter;
            this.capacity = new Semaphore(capacity);
        }
    }

    /**
     * 排队中的请求。
     */
    private static final class PendingRequest {

        /**
         * 请求
         */
        private final ServerRequest request;

        /**
         * 响应
         */
        private final ServerResponse response;

        /**
         * 下游过滤器链
         */
        private final ReactiveFilterChain chain;

        /**
         * 排队截止时间（nanoTime）
         */
        private final long deadlineNanos;

        /**
         * 完成信号：排队请求最终放行/拒绝后完成
         */
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        /**
         * 创建排队请求。
         *
         * @param request       请求
         * @param response      响应
         * @param chain         下游过滤器链
         * @param deadlineNanos 排队截止时间
         */
        PendingRequest(ServerRequest request, ServerResponse response, ReactiveFilterChain chain, long deadlineNanos) {
            this.request = request;
            this.response = response;
            this.chain = chain;
            this.deadlineNanos = deadlineNanos;
        }

        /**
         * 是否已超过排队截止时间。
         *
         * @return true 已超时
         */
        boolean isExpired() {
            return System.nanoTime() - deadlineNanos >= 0;
        }
    }
}
