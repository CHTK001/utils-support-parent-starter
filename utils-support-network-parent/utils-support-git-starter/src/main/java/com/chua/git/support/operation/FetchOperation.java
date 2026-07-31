package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.listener.GitFileListener;
import com.chua.git.support.listener.GitProgressListener;
import com.chua.git.support.model.PullResult;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 拉取操作（git pull），同步/异步执行并支持进度及文件变更回调。
 *
 * <p>该类本身是链式构建的一个环节，最终通过以下任一方式触发执行：</p>
 * <ol>
 *   <li>{@link #execute()} — 一次同步(或异步) pull，返回结果</li>
 *   <li>{@link #start()} — 启动后台定时轮询 pull + 文件 diff 通知</li>
 * </ol>
 *
 * <pre>典型链式用法：
 * {@code
 * // 1. 同步
 * PullResult r = client.open().pull().execute();
 *
 * // 2. 异步
 * CompletableFuture<PullResult> f = (CompletableFuture<PullResult>) client.pull().async().execute();
 *
 * // 3. 定时监听
 * client.pull()
 *     .interval(30, TimeUnit.SECONDS)
 *     .listener(e -> System.out.println(e.changeType() + ": " + e.filePath()))
 *     .progressListener(new ConsoleProgress())
 *     .start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FetchOperation {

    /**
     * 所属 GitClient。
     */
    private final GitClient client;

    /**
     * 异步标记：为 true 时 {@link #execute()} 返回 CompletableFuture。
     */
    private boolean asyncMode;

    /**
     * 定时轮询间隔（秒），默认 30。
     */
    private long watchInterval = 30;

    /**
     * 轮询间隔单位，默认 TimeUnit.SECONDS。
     */
    private TimeUnit watchTimeUnit = TimeUnit.SECONDS;

    /**
     * 文件变更监听器，不为 null 时执行 diff。
     */
    private GitFileListener watchListener;

    /**
     * 进度监听器，透传到底层 JGit ProgressMonitor。
     */
    private GitProgressListener progressListener;

    /**
     * 定时拉取使用的调度线程。
     */
    private ScheduledExecutorService scheduler;

    /**
     * 构建操作实例（仅框架内部调用）。
     *
     * @param client 所属 GitClient
     */
    public FetchOperation(GitClient client) {
        this.client = client;
    }

    // ==================== 链式配置方法 ====================

    /**
     * 标记为异步模式：{@link #execute()} 将返回 {@link CompletableFuture}。
     *
     * @return 当前操作实例（链式衔接）
     */
    public FetchOperation async() {
        this.asyncMode = true;
        return this;
    }

    /**
     * 设置进度监听器。
     *
     * @param listener 进度监听器，非空
     * @return 当前操作实例
     */
    public FetchOperation progressListener(GitProgressListener listener) {
        this.progressListener = listener;
        return this;
    }

    /**
     * 设置轮询（watch）间隔。
     *
     * @param interval 间隔数值
     * @param unit     时间单位
     * @return 当前操作实例
     */
    public FetchOperation interval(long interval, TimeUnit unit) {
        this.watchInterval = interval;
        this.watchTimeUnit = unit;
        return this;
    }

    /**
     * 设置文件变更监听器（与 {@link #start()} 配合使用）。
     *
     * @param listener 文件变更监听器，非 null
     * @return 当前操作实例
     */
    public FetchOperation listener(GitFileListener listener) {
        this.watchListener = listener;
        return this;
    }

    // ==================== 执行方法 ====================

    /**
     * 执行一次拉取。
     *
     * @return 同步模式返回 {@link PullResult}，异步模式返回 {@link CompletableFuture}{@code <PullResult>}
     */
    public Object execute() {
        if (asyncMode) {
            return CompletableFuture.supplyAsync(() -> client.pull(watchListener, progressListener));
        }
        return client.pull(watchListener, progressListener);
    }

    /**
     * 启动定时拉取监听。
     *
     * <p>后台守护线程会以固定间隔执行 {@code git pull}，
     * 若 pull 后 HEAD 树变化，再 diff 出文件列表通知 {@link #watchListener}。</p>
     *
     * <p>必须先调用 {@link #listener(GitFileListener)} 后再调用该方法。</p>
     *
     * @throws GitClientException 如果未设置监听器
     */
    public void start() {
        if (watchListener == null) {
            throw new GitClientException("监听器不能为空，请先调用 listener()");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-watch-" + client.getRepository().getDirectory().getParent());
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                log.debug("Git watch: 检查变更...");
                client.pull(watchListener, progressListener);
            } catch (Exception e) {
                log.error("Git watch 拉取失败", e);
            }
        }, 0, watchInterval, watchTimeUnit);
        log.info("Git 文件监听已启动, 间隔={} {}", watchInterval, watchTimeUnit);
    }

    /**
     * 停止由 {@link #start()} 启动的定时拉取。
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}