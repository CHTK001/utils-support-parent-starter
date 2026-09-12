package com.chua.starter.datasync;

import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
   * 默认执行器管理器，按 topic 池化管理 reactor数据同步执行器 实例。
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

    /** 服务标识 */
    private final String serverId;
    /** 是否直接分发 */
    private final boolean directDispatch;
    /** 执行器映射 */
    private final Map<String, ReactorDataSyncExecutor> executors = new ConcurrentHashMap<>();
    /** 是否已启动 */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
      * 创建 默认执行器管理器 实例
     * @param serverId 服务端标识
     */
    public DefaultExecutorManager(String serverId) {
        this(serverId, false);
    }

    /**
      * 创建 默认执行器管理器 实例
     * @param serverId 服务端标识
     * @param directDispatch 布尔值
     * @param directDispatch directdispatch
     */
    public DefaultExecutorManager(String serverId, boolean directDispatch) {
        this.serverId = serverId;
        this.directDispatch = directDispatch;
    }

    @Override
    /** 开始 */
    public void start() {
        started.set(true);
        log.info("ExecutorManager 已启动，serverId={}, 执行器数量={}, directDispatch={}", serverId, executors.size(), directDispatch);
    }

    @Override
    /** 停止 */
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
    /** 获取执行器 */
    public ReactorDataSyncExecutor getExecutor(String topic) {
        return executors.computeIfAbsent(topic, t -> {
            ReactorDataSyncExecutor executor = new ReactorDataSyncExecutor(t, true);
            executor.setDirectDispatch(directDispatch);
            if (started.get()) {
                executor.start();
                log.debug("懒启动执行器: topic={}, directDispatch={}", t, directDispatch);
            }
            return executor;
        });
    }

    /**
     * 获取执行器计算数量
     *
     * @return 获取执行器数量的结果
     */
    public int getExecutorCount() {
        return executors.size();
    }
}