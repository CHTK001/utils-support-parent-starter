package com.chua.common.support.rule.file;

import com.chua.common.support.lang.directory.DirectoryWatcher;
import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.rule.RuleException;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则热更新器。
 *
 * <p>监听规则文件所在目录，一旦文件被创建或修改，就触发
 * {@link RuleRepository#reload()} 完成重新装载与原子换入。</p>
 *
 * <h3>为什么需要去抖（debounce）</h3>
 * <p>编辑器保存一次文件往往触发多次事件（写入、截断、权限变更），
 * 若每次都重载，会产生大量无意义的解析与装配；
 * 而在写入尚未完成时 reload 又会读到半截文件导致装载失败。
 * 因此本类采用<b>去抖 + 延迟重试</b>两步：</p>
 * <ol>
 *   <li><b>去抖</b>：收到事件后等待 {@link Builder#debounceMillis(long)}
 *       毫秒内不再有新事件才真正重载，合并一次保存产生的多次事件；</li>
 *   <li><b>延迟重试</b>：若重载失败（多半是文件仍在写入），
 *       按 {@link Builder#retryDelayMillis(long)} 间隔重试，
 *       最多 {@link Builder#maxRetries(int)} 次。
 *       没有这一步，一次「先清空再写入」的保存方式会一直停留在
 *       上一份规则集上，直到下一次文件事件才恢复。</li>
 * </ol>
 *
 * <p><b>关于计数</b>：一次文件保存通常会产生多个 WatchService 事件，
 * 每个事件各自触发一条重载链，因此 {@link #failureCount()} 统计的是
 * <b>失败的尝试次数</b>而非「失败的文件变更次数」，
 * 也可能大于 {@code maxRetries + 1}。重试上限约束的是
 * <b>每条链</b>，以此保证不会无限重试。</p>
 *
 * <h3>失败不影响线上</h3>
 * <p>重载失败时 {@link RuleRepository} 会保留上一份可用规则集
 * （见其文档），因此一份写坏的规则文件不会让门禁整体失效。
 * 本类会把失败计入 {@link #failureCount()} 并通过
 * {@link RuleRepository#addListener} 上报，便于告警。</p>
 *
 * <p>注意：失败<b>不会</b>以异常形式抛出——仓库刻意吞掉异常以保证可用性，
 * 所以判定成功与否要看 {@link RuleRepository#lastError()}，
 * 而不是「有没有抛异常」。</p>
 *
 * <h3>线程模型</h3>
 * <p>使用单线程 {@link ScheduledExecutorService} 串行执行重载，
 * 因此同一时刻不会有两次重载并发；该线程为守护线程，
 * 不阻止 JVM 退出。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * RuleRepository repository = RuleRepository.builder()
 *         .assembler(new RuleAssembler(types, RuleActionRegistry.createDefault()))
 *         .build();
 * repository.loadFromDirectory(Paths.get("rules"));
 *
 * try (RuleHotReloader reloader = RuleHotReloader.builder(repository)
 *         .directory(Paths.get("rules"))
 *         .debounceMillis(300)
 *         .build()) {
 *     reloader.start();
 *     // ... 规则文件变更会自动热更新
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleHotReloader implements AutoCloseable {

    /**
     * 默认去抖毫秒数
     */
    private static final long DEFAULT_DEBOUNCE_MILLIS = 300L;

    /**
     * 默认重试间隔毫秒数
     */
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 200L;

    /**
     * 默认最大重试次数
     */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * 目标仓库
     */
    private final RuleRepository repository;

    /**
     * 被监听目录
     */
    private final Path directory;

    /**
     * 去抖时长
     */
    private final long debounceMillis;

    /**
     * 重试间隔
     */
    private final long retryDelayMillis;

    /**
     * 最大重试次数
     */
    private final int maxRetries;

    /**
     * 目录监听器
     */
    private final DirectoryWatcher watcher;

    /**
     * 调度线程
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * 累计重载次数
     */
    private final AtomicLong reloadCount = new AtomicLong();

    /**
     * 累计失败次数。
     *
     * <p>含重试：一次事件触发的多次失败尝试会分别计数，
     * 因此该值可能大于「失败的文件变更次数」。</p>
     */
    private final AtomicLong failureCount = new AtomicLong();

    /**
     * 创建热更新器。
     *
     * @param repository       目标仓库
     * @param directory        被监听目录
     * @param debounceMillis   去抖时长
     * @param retryDelayMillis 重试间隔
     * @param maxRetries       最大重试次数
     */
    private RuleHotReloader(RuleRepository repository, Path directory, long debounceMillis,
            long retryDelayMillis, int maxRetries) {
        if (repository == null) {
            throw new RuleException("规则仓库不能为 null");
        }
        if (directory == null) {
            throw new RuleException("监听目录不能为 null");
        }
        if (debounceMillis < 0) {
            throw new RuleException("去抖时长不能为负数");
        }
        if (retryDelayMillis < 0) {
            throw new RuleException("重试间隔不能为负数");
        }
        if (maxRetries < 0) {
            throw new RuleException("最大重试次数不能为负数");
        }
        this.repository = repository;
        this.directory = directory;
        this.debounceMillis = debounceMillis;
        this.retryDelayMillis = retryDelayMillis;
        this.maxRetries = maxRetries;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rule-hot-reloader");
            thread.setDaemon(true);
            return thread;
        });
        this.watcher = new DirectoryWatcher(directory.toAbsolutePath().toString());
        this.watcher.addListener(new ReloadListener());
    }

    /**
     * 创建构建器。
     *
     * @param repository 目标仓库
     * @return 构建器
     */
    public static Builder builder(RuleRepository repository) {
        return new Builder(repository);
    }

    /**
     * 启动监听。
     *
     * @return 当前热更新器
     */
    public RuleHotReloader start() {
        if (started.compareAndSet(false, true)) {
            watcher.start();
        }
        return this;
    }

    /**
     * 停止监听并释放调度线程。
     */
    public void stop() {
        if (started.compareAndSet(true, false)) {
            try {
                watcher.stop();
            } catch (RuntimeException ignored) {
                // 停止异常不影响进程退出
            }
        }
        scheduler.shutdownNow();
    }

    /**
     * 是否已启动。
     *
     * @return 已启动返回 true
     */
    public boolean isRunning() {
        return started.get();
    }

    /**
     * 获取累计重载次数。
     *
     * @return 重载次数
     */
    public long reloadCount() {
        return reloadCount.get();
    }

    /**
     * 获取累计失败次数。
     *
     * @return 失败次数
     */
    public long failureCount() {
        return failureCount.get();
    }

    /**
     * 立即执行一次重载（忽略去抖与重试），供外部在批量改动后主动触发。
     *
     * <p>本方法只尝试一次：调用方明确知道自己在做什么，
     * 是否需要再次重试由调用方决定。</p>
     *
     * @return 重载后的版本号
     */
    public long reloadNow() {
        return doReload(maxRetries);
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * 执行重载并统计结果。
     *
     * @return 重载后的版本号
     */
    /**
     * 执行一次重载；失败时按需安排重试。
     *
     * @param attempt 当前第几次尝试，从 0 开始
     * @return 重载后的版本号
     */
    private long doReload(int attempt) {
        boolean success;
        long version;
        try {
            version = repository.reload();
            // 关键：装载失败时 RuleRepository 并不会抛异常——
            // 它会保留上一份可用规则集并把错误放进 lastError()。
            // 因此必须看 lastError 而不能只看有没有抛异常，
            // 否则写坏的规则文件会被误计为「重载成功」，告警就失效了。
            success = repository.lastError() == null;
        } catch (RuntimeException e) {
            // 首次装载（尚无可用规则集）失败时仓库会直接抛出
            success = false;
            version = repository.version();
        }
        if (success) {
            reloadCount.incrementAndGet();
        } else {
            failureCount.incrementAndGet();
            scheduleRetry(attempt);
        }
        return version;
    }

    /**
     * 安排一次延迟重试。
     *
     * <p>只在仍处于运行状态、且未超过重试上限时安排；
     * {@code stop()} 之后不再重试，避免停机后仍有后台动作。</p>
     *
     * @param attempt 当前第几次尝试
     */
    private void scheduleRetry(int attempt) {
        if (attempt >= maxRetries) {
            return;
        }
        if (!started.get()) {
            return;
        }
        scheduler.schedule(() -> doReload(attempt + 1), retryDelayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 判断是否为需要响应的规则文件。
     *
     * @param path 文件路径
     * @return 是规则文件返回 true
     */
    private static boolean isRuleFile(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".jsonl");
    }

    /**
     * 目录变更监听器：去抖后触发重载。
     */
    private final class ReloadListener implements PolledListener {

        @Override
        public void onCreate(WatcherEvent event, EventObserver observer) {
            trigger(observer);
        }

        @Override
        public void onModify(WatcherEvent event, EventObserver observer) {
            trigger(observer);
        }

        @Override
        public void onDelete(WatcherEvent event, EventObserver observer) {
            trigger(observer);
        }

        /**
         * 触发一次去抖重载。
         *
         * @param observer 事件观察者
         */
        private void trigger(EventObserver observer) {
            if (observer == null || !isRuleFile(observer.getFullPath())) {
                return;
            }
            if (!started.get()) {
                return;
            }
            // 每次事件都重新排定任务，实现「最后一次事件后再执行」
            scheduler.schedule(() -> RuleHotReloader.this.doReload(0),
                    debounceMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 热更新器构建器。
     */
    public static final class Builder {

        /**
         * 目标仓库
         */
        private final RuleRepository repository;

        /**
         * 监听目录
         */
        private Path directory;

        /**
         * 去抖时长
         */
        private long debounceMillis = DEFAULT_DEBOUNCE_MILLIS;

        /**
         * 重试间隔
         */
        private long retryDelayMillis = DEFAULT_RETRY_DELAY_MILLIS;

        /**
         * 最大重试次数
         */
        private int maxRetries = DEFAULT_MAX_RETRIES;

        /**
         * 创建构建器。
         *
         * @param repository 目标仓库
         */
        private Builder(RuleRepository repository) {
            this.repository = repository;
        }

        /**
         * 设置监听目录；缺省取仓库当前来源。
         *
         * @param directory 目录
         * @return 当前构建器
         */
        public Builder directory(Path directory) {
            this.directory = directory;
            return this;
        }

        /**
         * 设置去抖时长。
         *
         * @param debounceMillis 去抖毫秒数
         * @return 当前构建器
         */
        public Builder debounceMillis(long debounceMillis) {
            this.debounceMillis = debounceMillis;
            return this;
        }

        /**
         * 设置重试间隔：重载失败后等待多久再试一次。
         *
         * <p>用于兜住「文件仍在写入」导致的半截文件解析失败。
         * 设得过大则规则恢复变慢，建议不小于 100 毫秒。</p>
         *
         * @param retryDelayMillis 重试间隔毫秒数
         * @return 当前构建器
         */
        public Builder retryDelayMillis(long retryDelayMillis) {
            this.retryDelayMillis = retryDelayMillis;
            return this;
        }

        /**
         * 设置最大重试次数；0 表示失败后不重试。
         *
         * <p>约束的是<b>单个文件事件触发的那条重载链</b>：
         * 该链最多尝试 {@code maxRetries + 1} 次（首次 + 重试）。
         * 一次文件保存通常产生多个事件、进而多条链，
         * 所以 {@link RuleHotReloader#failureCount()} 可能大于此值——
         * 它统计的是失败尝试次数。上限的意义在于保证不会无限重试。</p>
         *
         * @param maxRetries 最大重试次数
         * @return 当前构建器
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * 构建热更新器。
         *
         * @return 热更新器
         * @throws RuleException 未显式指定目录且仓库无来源时抛出
         */
        public RuleHotReloader build() {
            Path target = directory != null ? directory : repository.source();
            if (target == null) {
                throw new RuleException("未指定监听目录，且规则仓库尚无来源");
            }
            return new RuleHotReloader(repository, target, debounceMillis,
                    retryDelayMillis, maxRetries);
        }
    }
}
