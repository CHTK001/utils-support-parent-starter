package com.chua.common.support.lang.directory.executor;

import com.chua.common.support.lang.directory.DiffPolledDirectory;
import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于虚拟线程的目录轮询执行器。
 * <p>
 * 使用 JDK 虚拟线程驱动 {@link PolledDirectory#upgrade()} 或 {@link DiffPolledDirectory#upgrade()} 的周期性调用，
 * 适用于 FTP、SFTP、数据库 CDC 等需要定时快照对比的场景。
 * </p>
 * <p>
 * 虚拟线程特性：轻量、无需池化，每个轮询任务独立创建，结束时自动回收。
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
@Slf4j
public class VirtualThreadPollerExecutor implements DirectoryPollerExecutor {

    /**
     * 被驱动的可轮询目录实例
     */
    private final PolledDirectory polledDirectory;

    /**
     * 环境配置（获取轮询间隔）
     */
    private final DirectoryPollerEnvironment environment;

    /**
     * 虚拟线程执行器
     */
    private final ExecutorService executor;

    /**
     * 运行状态标志
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造虚拟线程轮询执行器。
     *
     * @param polledDirectory 可轮询目录实例
     * @param environment     环境配置
     */
    public VirtualThreadPollerExecutor(PolledDirectory polledDirectory, DirectoryPollerEnvironment environment) {
        this.polledDirectory = polledDirectory;
        this.environment = environment;
        this.executor = ThreadUtils.newVirtualThreadPerTaskExecutor("DirPoller");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        // 使用虚拟线程周期性执行轮询
        executor.execute(() -> {
            long interval = environment.getPollingInterval();
            TimeUnit timeUnit = environment.getTimeUnit();

            while (running.get()) {
                try {
                    polledDirectory.upgrade();
                } catch (Exception e) {
                    log.error("轮询执行异常", e);
                }

                try {
                    timeUnit.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        log.info("虚拟线程轮询已启动, 间隔={} {}", environment.getPollingInterval(), environment.getTimeUnit());
    }

    @Override
    public void close() {
        running.set(false);
        if (executor != null) {
            ThreadUtils.shutdownNow(executor);
        }
    }
}
