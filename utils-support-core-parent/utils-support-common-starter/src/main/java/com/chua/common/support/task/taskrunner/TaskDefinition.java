package com.chua.common.support.task.taskrunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 任务定义 — TaskRunner 中单个任务节点的声明式配置。
 *
 * <p>通过 {@code TaskRunner#task(String, Function)} 注册并返回本实例，
 * 以链式调用完成依赖、超时、重试、熔断降级等配置：</p>
 *
 * <pre>{@code
 * runner.task("flaky", ctx -> unstableCall())
 *         .afterNode("a", "b")              // 控制流依赖：a、b 完成后才执行
 *         .timeout(Duration.ofMillis(500))  // 单独超时（未设置时用 runner 全局值）
 *         .retry(5)                         // 单独重试次数（未设置时用 runner 全局值）
 *         .failureThreshold(3)              // 连续失败 3 次熔断打开
 *         .waitDuration(10_000)             // 熔断打开后半开等待毫秒
 *         .circuitBreaker()                 // 启用熔断（与上述参数同级）
 *         .fallback(ctx -> cachedValue());  // 失败或熔断打开时的降级兜底
 * }</pre>
 *
 * <p>依赖语义：</p>
 * <ul>
 *   <li>{@link #afterNode(String...)} — 纯控制流依赖：前置完成后即触发，不读取其结果</li>
 *   <li>{@link #dependsNode(String...)} — 数据依赖：前置完成后校验其结果非 null，
 *       缺失则本节点判失败（可被 fallback 兜底）；隐含控制流语义</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TaskDefinition {

    /**
     * 重试退避基础间隔毫秒
     */
    private static final long BACKOFF_BASE_MS = 200L;

    /**
     * 重试退避最大间隔毫秒
     */
    private static final long BACKOFF_CAP_MS = 2_000L;

    /**
     * 节点 ID，运行内唯一
     */
    private final String id;

    /**
     * 任务执行函数
     */
    private final Function<RunnerContext, Object> action;

    /**
     * 控制流依赖的前置节点 ID 集合（保持注册顺序）
     */
    private final List<String> dependencies = new ArrayList<>();

    /**
     * 数据依赖的前置节点 ID 集合（requires 非 null 结果）
     */
    private final Set<String> dataDependencies = new LinkedHashSet<>();

    /**
     * 单独超时，null 表示使用 runner 全局值
     */
    private Duration timeout;

    /**
     * 单独重试次数，null 表示使用 runner 全局值
     */
    private Integer retryCount;

    /**
     * 熔断失败阈值，-1 表示未设置（启用熔断且未设置时取默认值 5）
     */
    private int failureThreshold = -1;

    /**
     * 半开恢复成功阈值，-1 表示未设置（默认 2）
     */
    private int successThreshold = -1;

    /**
     * 熔断打开后半开等待毫秒，-1 表示未设置（默认 60000）
     */
    private long waitDurationMs = -1L;

    /**
     * 是否启用熔断保护
     */
    private boolean circuitBreakerEnabled;

    /**
     * 降级兜底函数，null 表示无降级
     */
    private Function<RunnerContext, Object> fallback;

    /**
     * 创建任务定义（由 TaskRunner#task 调用）。
     *
     * @param id     节点 ID，不为空
     * @param action 执行函数，不为 null
     */
    TaskDefinition(String id, Function<RunnerContext, Object> action) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("节点 id 不能为空");
        }
        this.id = id;
        this.action = Objects.requireNonNull(action, "action must not be null");
    }

    /**
     * 追加控制流依赖：声明的全部前置节点完成后才执行本节点。
     *
     * @param ids 前置节点 ID，至少一个且不能为空
     * @return 当前定义
     */
    public TaskDefinition afterNode(String... ids) {
        requireIds(ids);
        dependencies.addAll(List.of(ids));
        return this;
    }

    /**
     * 追加数据依赖：前置节点完成后校验其结果非 null，缺失则本节点判失败。
     *
     * <p>同时具备 {@link #afterNode(String...)} 的控制流语义。</p>
     *
     * @param ids 前置节点 ID，至少一个且不能为空
     * @return 当前定义
     */
    public TaskDefinition dependsNode(String... ids) {
        requireIds(ids);
        for (var dep : ids) {
            if (!dependencies.contains(dep)) {
                dependencies.add(dep);
            }
            dataDependencies.add(dep);
        }
        return this;
    }

    /**
     * 设置单独超时时间，覆盖 runner 全局值。
     *
     * <p>超时约束整个"重试序列"的总耗时，而非单次尝试。</p>
     *
     * @param d 超时时长，必须为正
     * @return 当前定义
     */
    public TaskDefinition timeout(Duration d) {
        Objects.requireNonNull(d, "timeout must not be null");
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正时长, got: " + d);
        }
        this.timeout = d;
        return this;
    }

    /**
     * 设置单独重试次数，覆盖 runner 全局值。
     *
     * @param n 最大重试次数（不含首次执行），必须 ≥ 0
     * @return 当前定义
     */
    public TaskDefinition retry(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("retry 必须 >= 0, got: " + n);
        }
        this.retryCount = n;
        return this;
    }

    /**
     * 设置熔断失败阈值：连续失败达到该次数后熔断打开。
     *
     * @param n 失败次数阈值，必须 ≥ 1
     * @return 当前定义
     */
    public TaskDefinition failureThreshold(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("failureThreshold 必须 >= 1, got: " + n);
        }
        this.failureThreshold = n;
        return this;
    }

    /**
     * 设置半开恢复成功阈值：半开状态下连续成功该次数后熔断关闭。
     *
     * @param n 成功次数阈值，必须 ≥ 1
     * @return 当前定义
     */
    public TaskDefinition successThreshold(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("successThreshold 必须 >= 1, got: " + n);
        }
        this.successThreshold = n;
        return this;
    }

    /**
     * 设置熔断打开后的半开等待时长（毫秒）。
     *
     * @param ms 等待毫秒数，必须 > 0
     * @return 当前定义
     */
    public TaskDefinition waitDuration(long ms) {
        if (ms <= 0) {
            throw new IllegalArgumentException("waitDuration 必须 > 0, got: " + ms);
        }
        this.waitDurationMs = ms;
        return this;
    }

    /**
     * 启用熔断保护。
     *
     * <p>未显式设置的阈值参数取默认值：failureThreshold=5、successThreshold=2、
     * waitDuration=60000ms。熔断状态按节点 ID 跨多次 run 持久生效。</p>
     *
     * @return 当前定义
     */
    public TaskDefinition circuitBreaker() {
        this.circuitBreakerEnabled = true;
        return this;
    }

    /**
     * 按条件启用或关闭熔断保护。
     *
     * @param enable true 启用
     * @return 当前定义
     */
    public TaskDefinition circuitBreaker(boolean enable) {
        this.circuitBreakerEnabled = enable;
        return this;
    }

    /**
     * 设置降级兜底函数：任务失败（重试耗尽）、超时或熔断拒绝时，
     * 以降级返回值作为本节点的成功结果。
     *
     * @param f 降级函数，不为 null
     * @return 当前定义
     */
    public TaskDefinition fallback(Function<RunnerContext, Object> f) {
        this.fallback = Objects.requireNonNull(f, "fallback must not be null");
        return this;
    }

    /**
     * 解析实际生效的超时时长。
     *
     * @param globalTimeout runner 全局超时，可为 null
     * @return 实际超时；两者均未设置时为 null（不限时）
     */
    Duration resolveTimeout(Duration globalTimeout) {
        return timeout != null ? timeout : globalTimeout;
    }

    /**
     * 解析实际生效的重试次数。
     *
     * @param globalRetry runner 全局重试次数
     * @return 实际重试次数
     */
    int resolveRetry(int globalRetry) {
        return retryCount != null ? retryCount : globalRetry;
    }

    /**
     * 计算第 attempt 次失败后的退避间隔（指数增长，封顶 2 秒）。
     *
     * @param attempt 从 0 开始的失败序号
     * @return 退避毫秒数
     */
    long backoffMillis(int attempt) {
        var scaled = BACKOFF_BASE_MS << Math.min(attempt, 4);
        return Math.min(scaled, BACKOFF_CAP_MS);
    }

    /**
     * 校验依赖 ID 数组合法性。
     *
     * @param ids 待校验数组
     */
    private static void requireIds(String[] ids) {
        Objects.requireNonNull(ids, "依赖 id 数组不能为 null");
        if (ids.length == 0) {
            throw new IllegalArgumentException("依赖 id 至少一个");
        }
        for (var dep : ids) {
            if (dep == null || dep.isBlank()) {
                throw new IllegalArgumentException("依赖 id 不能为空");
            }
        }
    }

    public String getId() {
        return id;
    }

    public Function<RunnerContext, Object> getAction() {
        return action;
    }

    public List<String> getDependencies() {
        return List.copyOf(dependencies);
    }

    public Set<String> getDataDependencies() {
        return Set.copyOf(dataDependencies);
    }

    public boolean hasFallback() {
        return fallback != null;
    }

    public Function<RunnerContext, Object> getFallback() {
        return fallback;
    }

    public boolean isCircuitBreakerEnabled() {
        return circuitBreakerEnabled;
    }

    public int getFailureThreshold() {
        return failureThreshold >= 1 ? failureThreshold : 5;
    }

    public int getSuccessThreshold() {
        return successThreshold >= 1 ? successThreshold : 2;
    }

    public long getWaitDuration() {
        return waitDurationMs > 0 ? waitDurationMs : 60_000L;
    }
}
