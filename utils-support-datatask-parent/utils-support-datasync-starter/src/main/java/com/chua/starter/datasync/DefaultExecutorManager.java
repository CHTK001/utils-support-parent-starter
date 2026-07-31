package com.chua.starter.datasync;

import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认执行器管理器，按 topic 池化管理 ReactorDataSyncExecutor 实例。
 *
 * <p>特性：
 * <ul>
 *   <li>懒启动：第一次获取时初始化，而非提前创建</li>
 *   <li>池化：同 topic 复用同一实例，避免频繁创建</li>
 *   <li>统一生命周期：start()/stop() 统一管理所有执行器</li>
 *   <li>线程安全：ConcurrentHashMap 存储，无锁并发访问</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultExecutorManager implements ExecutorManager {

    /**
     * 服务器标识
     */
    private final String serverId;

    /**
     * 执行器缓存（topic -> executor）
     */
    private final Map<String, ReactorDataSyncExecutor> executors = new ConcurrentHashMap<>();

    /**
     * 启动状态标记
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    public DefaultExecutorManager(String serverId) {
        this.serverId = serverId;
    }

    @Override
    public void start() {
        started.set(true);
        log.info("ExecutorManager 已启动，serverId={}, 执行器数量={}", serverId, executors.size());
    }

    @Override
    public void stop() {
        started.set(false);
        executors.values().forEach(executor -> {
            try {
                executor.stop();
            } catch (Exception e) {
                log.warn("关闭执行器异常", e);
            }
        });
        executors.clear();
        log.info("ExecutorManager 已停止");
    }

    @Override
    public ReactorDataSyncExecutor getExecutor(String topic) {
        return executors.computeIfAbsent(topic, t -> {
            ReactorDataSyncExecutor executor = new ReactorDataSyncExecutor(t, true);
            if (started.get()) {
                executor.start();
                log.debug("懒启动执行器: topic={}", t);
            }
            return executor;
        });
    }

    /**
     * 获取当前已创建的执行器数量。
     *
     * @return 执行器数量
     */
    public int getExecutorCount() {
        return executors.size();
    }
}