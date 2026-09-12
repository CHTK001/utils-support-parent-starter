package com.chua.starter.datasync;

import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;

/**
   * 执行器管理器，负责按 topic 管理 reactor数据同步执行器 的生命周期。
 *
 * <p>实现懒启动和执行器池化，避免为每个映射频繁创建执行器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ExecutorManager {

    /**
     * 启动所有执行器。
     */
    void start();

    /**
     * 关闭所有执行器。
     */
    void stop();

    /**
     * 根据 topic 获取执行器实例（懒启动）。
     *
     * @param topic 主题标识
     * @return ReactorDataSyncExecutor 实例
     */
    ReactorDataSyncExecutor getExecutor(String topic);
}