package com.chua.datalake.support.executor;

import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.chua.datalake.support.spi.sink.DataSink;
import com.chua.starter.datasync.ExecutorManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 数据湖 侧的 执行器管理器 实现。每次返回同一个 数据湖reactor执行器。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DatalakeExecutorManager implements ExecutorManager {

    /**
     * 内部的唯一执行器实例
     */
    private final DatalakeReactorExecutor executor;

    /**
     * 注入 dispatcher提供者，使 执行器 能共享 Chronicle 队列。
     *
     * @param dispatcherProvider dispatcher提供者 实例
     */
    public void setDispatcherProvider(DispatcherProvider dispatcherProvider) {
        executor.setDispatcherProvider(dispatcherProvider);
    }

    /**
     * 创建 数据湖执行器管理器 实例
    */
    public DatalakeExecutorManager() {
        this.executor = new DatalakeReactorExecutor("datalake", true);
    }

    /**
     * 注入管线引擎，并同时为执行器注册 sink（用于 降级 直接派发）。
     *
     * @param unused 兼容参数
     * @param engine 管线引擎
     * @param sinks  sink 注册表
     */
    public void setPipelineEngine(ReactorDataSyncExecutor unused, PipelineEngine engine, Map<String, DataSink> sinks) {
        executor.setPipelineEngine(engine);
        if (sinks != null) {
            sinks.values().forEach(executor::registerSink);
        }
    }

    /**
     * 仅注入管线引擎。
     *
     * @param unused 兼容参数
     * @param engine 管线引擎
     */
    public void setPipelineEngine(ReactorDataSyncExecutor unused, PipelineEngine engine) {
        executor.setPipelineEngine(engine);
    }

    @Override
    /**
     * 开始
    */
    public void start() {
        log.info("[datalake-server] ExecutorManager 启动");
    }

    @Override
    /**
     * 停止
    */
    public void stop() {
        log.info("[datalake-server] ExecutorManager 停止");
    }

    @Override
    /**
     * 获取执行器
    */
    public ReactorDataSyncExecutor getExecutor(String topic) {
        return executor;
    }

    /**
     * 获取内部执行器的 topic（用于跨进程 Chronicle 订阅）。
     *
     * @param sinkId sink 标识
     * @return topic 字符串
     */
    public String getTopic(String sinkId) {
        return "server:" + executor.getAgentId();
    }
}
