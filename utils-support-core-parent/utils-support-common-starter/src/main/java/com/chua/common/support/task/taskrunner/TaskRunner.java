package com.chua.common.support.task.taskrunner;

import com.chua.common.support.spi.ServiceProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 任务运行器门面 — 轻量级 DAG 任务编排 DSL。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li><strong>串行编排</strong>：{@code afterNode} / {@code dependsNode} 声明依赖，拓扑分层调度</li>
 *   <li><strong>并行合并</strong>：同层节点自动并行（Java 25 结构化并发），
 *       {@link CompletionPolicy} 决定整体成败（含成功率模式）</li>
 *   <li><strong>可靠性</strong>：全局/单任务超时与重试、熔断降级</li>
 *   <li><strong>三种出口</strong>：异步 {@link #execute}、同步 {@link #executeSync}、
 *       响应式 {@link #executeReactor}，事件经 {@link #watch()} 以 Flux 消费</li>
 *   <li><strong>SPI 扩展</strong>：执行引擎经 {@link RunnerProvider} 扩展点接入，
 *       默认 {@code structured}</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * var runner = TaskRunner.of("demo")
 *         .timeout(Duration.ofSeconds(10))
 *         .retry(3)
 *         .policy(CompletionPolicy.allSuccess())
 *         .task("a", ctx -> fetchA())
 *         .task("b", ctx -> fetchB()).afterNode("a")
 *         .task("c", ctx -> merge(ctx)).afterNode("a", "b");
 *
 * runner.execute(input);        // 异步 CompletableFuture<RunResult>
 * runner.executeSync(input);    // 同步
 * runner.executeReactor();      // Mono<RunResult>
 * }</pre>nResult>
 * runner.executeSync(input);    // 同步
 * runner.executeReactor();      // Mono<RunResult>
 * }</pre>
 *
 * <p>约束：{@code policy(...)} 为必填项，未设置时任何执行入口都会抛出
 * {@link IllegalStateException}。同一 runner 可多次执行；事件流为有界缓冲
 * （4096 条，溢出丢最旧），跨执行累积并以 运行_启动 分界，
 * 长期复用时可调用 {@link #clearEvents()} 重置。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TaskRunner {

    /**
     * 默认执行引擎 SPI 名称
     */
    private static final String DEFAULT_PROVIDER = "structured";

    /**
     * 运行名称
     */
    private final String name;

    /**
     * 注册顺序保持的节点定义表
     */
    private final Map<String, TaskDefinition> definitions = new LinkedHashMap<>();

    /**
     * 全局默认单任务超时，空 表示不限时
     */
    private Duration timeout;

    /**
     * 全局默认重试次数
     */
    private int retryCount;

    /**
     * 完成策略（必填）
     */
    private CompletionPolicy policy;

    /**
     * 用户注册的事件监听器
     */
    private final List<RunnerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 执行引擎 SPI 名称
     */
    private String providerName = DEFAULT_PROVIDER;

    /**
     * 事件缓冲容量上限，超出后丢弃最旧事件
     */
    private static final int EVENT_BUFFER_SIZE = 4096;

    /**
     * 事件流：unicast 单播 + 有界缓冲（溢出丢最旧），watch() 订阅消费
     */
    private volatile Sinks.Many<RunnerEvent> eventSink = newSink();

    /**
     * 事件流替换锁：clear事件 与 bridge事件 的互斥
     */
    private final Object sinkLock = new Object();

    /**
     * 拓扑结构版本号：任务() 注册时递增
     */
    private volatile int structureVersion;

    /**
     * 已缓存拓扑图对应的版本号
     */
    private volatile int cachedGraphVersion = -1;

    /**
     * 拓扑图缓存（定义不可变时避免重复构建校验）
     */
    private volatile TaskGraph cachedGraph;

    /**
     * 创建新的事件 sink。
     *
     * @return 有界单播 sink
     */
    private static Sinks.Many<RunnerEvent> newSink() {
        return Sinks.many().unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<RunnerEvent>(EVENT_BUFFER_SIZE));
    }

    /**
     * 私有构造，统一经 {@link #of(String)} 创建。
     *
     * @param name 运行名称
     */
    private TaskRunner(String name) {
        this.name = name;
    }

    /**
     * 创建任务运行器。
     *
     * @param name 运行名称，不为空
     * @return 运行器实例
     */
    public static TaskRunner of(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("运行名称不能为空");
        }
        return new TaskRunner(name);
    }

    /**
     * 设置全局默认单任务超时，可被节点级 超时 覆盖。
     *
     * @param d 超时时长，必须为正
     * @return 当前运行器
     */
    public TaskRunner timeout(Duration d) {
        Objects.requireNonNull(d, "timeout must not be null");
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正时长, got: " + d);
        }
        this.timeout = d;
        return this;
    }

    /**
     * 设置全局默认重试次数，可被节点级 重试 覆盖。
     *
     * @param n 重试次数（不含首次执行），必须 ≥ 0
     * @return 当前运行器
     */
    public TaskRunner retry(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("retry 必须 >= 0, got: " + n);
        }
        this.retryCount = n;
        return this;
    }

    /**
     * 设置完成策略（必填项）。
     *
     * @param p 完成策略，不为 空
     * @return 当前运行器
     */
    public TaskRunner policy(CompletionPolicy p) {
        this.policy = Objects.requireNonNull(p, "policy must not be null");
        return this;
    }

    /**
     * 注册事件监听器。
     *
     * @param l 监听器，不为 空
     * @return 当前运行器
     */
    public TaskRunner listener(RunnerListener l) {
        listeners.add(Objects.requireNonNull(l, "listener must not be null"));
        return this;
    }

    /**
     * 切换执行引擎 SPI 实现。
     *
     * @param providerName SPI 实现名，如 {@code "structured"}
     * @return 当前运行器
     */
    public TaskRunner provider(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("providerName 不能为空");
        }
        this.providerName = providerName;
        return this;
    }

    /**
     * 注册任务节点。
     *
     * @param id     节点 标识，运行内唯一且非空
     * @param action 执行函数，接收上下文并返回结果值
     * @return 任务定义，用于链式追加配置
     * @throws IllegalStateException 当 标识 重复注册时
     */
    public TaskDefinition task(String id, Function<RunnerContext, Object> action) {
        Objects.requireNonNull(action, "action must not be null");
        if (definitions.containsKey(id)) {
            throw new IllegalStateException("节点 id 已注册: " + id);
        }
        var def = new TaskDefinition(this, id, action);
        definitions.put(id, def);
        structureVersion++;
        return def;
    }

    /**
     * 异步执行整个拓扑图。
     *
     * <p>整个 DAG 在独立虚拟线程上运行，当前线程不阻塞。
     * 调用 {@code future.cancel(true)} 会中断执行线程，
     * 结构化并发作用域随之取消全部在途子任务。</p>
     *
     * @param input 初始输入，可为 空
     * @return 整体结果 期货；cancel 后以 cancellation异常 结束
     * @throws IllegalStateException 当未设置策略或未注册任务时
     */
    public CompletableFuture<RunResult> execute(Object input) {
        var prepared = prepare(input);
        var future = new CompletableFuture<RunResult>();
        var worker = Thread.ofVirtual().name("task-runner-" + name).start(() -> {
            try {
                future.complete(resolveProvider().run(prepared.graph(), prepared.context(), prepared.options()));
            } catch (Throwable t) {
                if (!future.isCancelled()) {
                    future.completeExceptionally(t);
                }
            }
        });
        // cancel(true) 时向执行线程传播中断（作用域 join 抛 InterruptedException 终止整图）
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) {
                worker.interrupt();
            }
        });
        return future;
    }

    /**
     * 同步执行整个拓扑图，在调用线程上阻塞完成。
     *
     * @param input 初始输入，可为 空
     * @return 整体结果
     * @throws IllegalStateException 当未设置策略或未注册任务时
     */
    public RunResult executeSync(Object input) {
        var prepared = prepare(input);
        return resolveProvider().run(prepared.graph(), prepared.context(), prepared.options());
    }

    /**
     * 响应式执行整个拓扑图。
     *
     * <p>完全惰性：参数校验与执行均推迟到订阅时刻。</p>
     *
     * @param input 初始输入，可为 空
     * @return 整体结果 Mono（订阅时校验并触发执行）
     */
    public Mono<RunResult> executeReactor(Object input) {
        return Mono.defer(() -> Mono.fromFuture(() -> execute(input)));
    }

    /**
     * 订阅运行事件流。
     *
     * <p>unicast 单播语义：同一时刻仅支持一个活跃订阅者。缓冲容量 4096，
     * 溢出时丢弃最旧事件；长期复用的 runner 可调用 {@link #clearEvents()}
     * 释放历史事件并重置订阅窗口。</p>
     *
     * @return 当前事件 Flux
     */
    public Flux<RunnerEvent> watch() {
        return eventSink.asFlux();
    }

    /**
     * 清空事件流：丢弃已缓冲事件并开启新的订阅窗口。
     *
     * <p>旧的 Flux 引用不再接收新事件；建议在两次 run 之间调用以防缓冲增长。</p>
     */
    public void clearEvents() {
        synchronized (sinkLock) {
            eventSink = newSink();
        }
    }

    /**
     * 执行前置准备：校验必填项、构建拓扑图、组装执行参数。
     *
     * @param input 初始输入
     * @return 预备数据
     * @throws IllegalStateException 当未设置策略或未注册任务时
     */
    private Prepared prepare(Object input) {
        if (policy == null) {
            throw new IllegalStateException(
                    "必须先调用 policy(...) 设置完成策略后才能执行，参考 CompletionPolicy.allSuccess()");
        }
        if (definitions.isEmpty()) {
            throw new IllegalStateException("未注册任何任务节点，请先调用 task(...)");
        }
        var graph = resolveGraph();
        var context = new RunnerContext(input);

        var merged = new ArrayList<RunnerListener>(listeners.size() + 1);
        merged.addAll(listeners);
        merged.add(this::bridgeEvent);
        var options = new RunnerProvider.ExecutionOptions(policy, timeout, retryCount, merged);
        return new Prepared(graph, context, options);
    }

    /**
     * 解析拓扑图：结构未变化时复用缓存，避免重复校验与分层计算。
     *
     * @return 拓扑图实例
     */
    private TaskGraph resolveGraph() {
        var version = structureVersion;
        var cached = cachedGraph;
        if (cached != null && cachedGraphVersion == version) {
            return cached;
        }
        var built = TaskGraph.of(name, definitions.values());
        cachedGraph = built;
        cachedGraphVersion = version;
        return built;
    }

    /**
     * 将监听器事件桥接到响应式事件流。
     *
     * <p>缓冲满时静默丢弃（有界策略），不阻塞执行线程。</p>
     *
     * @param event 待发布事件
     */
    private void bridgeEvent(RunnerEvent event) {
        synchronized (sinkLock) {
            eventSink.tryEmitNext(event);
        }
    }

    /**
     * 解析执行引擎：默认实现名允许 SPI 缺失时兜底；
     * 显式指定的其他名称找不到实现则立即报错，禁止静默降级。
     *
     * @return 执行引擎实例
     * @throws IllegalArgumentException 当指定名称未注册且不是默认名时
     */
    private RunnerProvider resolveProvider() {
        var resolved = ServiceProvider.of(RunnerProvider.class).getExtension(providerName);
        if (resolved != null) {
            return resolved;
        }
        if (DEFAULT_PROVIDER.equals(providerName)) {
            return new StructuredRunnerProvider();
        }
        throw new IllegalArgumentException(
                "未知的 RunnerProvider 实现: " + providerName + "（当前可用: " + DEFAULT_PROVIDER + "）");
    }

    /**
     * 预备数据内部载体。
     *
     * @param graph   拓扑图
     * @param context 运行上下文
     * @param options 执行参数
     */
    private record Prepared(TaskGraph graph, RunnerContext context,
                            RunnerProvider.ExecutionOptions options) {
    }
}
