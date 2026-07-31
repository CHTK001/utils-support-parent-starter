package com.chua.datalake.support.executor;

import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.chua.datalake.support.spi.sink.DataSink;
import com.chua.starter.datasync.ExecutorManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Datalake 侧的 ExecutorManager 实现。每次返回同一个 DatalakeReactorExecutor。
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class DatalakeExecutorManager implements ExecutorManager {

    /**
     * 内部的唯一执行器实例
     */
    private final DatalakeReactorExecutor executor;

    /**
     * 注入 DispatcherProvider，使 executor 能共享 Chronicle 队列。
     */
    public void setDispatcherProvider(DispatcherProvider dispatcherProvider) {
        executor.setDispatcherProvider(dispatcherProvider);
    }

    public DatalakeExecutorManager() {
        this.executor = new DatalakeReactorExecutor("datalake", true);
    }

    /**
     * 注入管线引擎，并同时为执行器注册 sink（用于 fallback 直接派发）。
     */
    public void setPipelineEngine(ReactorDataSyncExecutor unused, PipelineEngine engine, Map<String, DataSink> sinks) {
        executor.setPipelineEngine(engine);
        if (sinks != null) {
            sinks.values().forEach(executor::registerSink);
        }
    }

    /**
     * 仅注入管线引擎。
     */
    public void setPipelineEngine(ReactorDataSyncExecutor unused, PipelineEngine engine) {
        executor.setPipelineEngine(engine);
    }

    @Override
    public void start() {
        log.info("DatalakeExecutorManager started");
    }

    @Override
    public void stop() {
        log.info("DatalakeExecutorManager stopped");
    }

    @Override
    public ReactorDataSyncExecutor getExecutor(String topic) {
        return executor;
    }

    /**
     * 获取内部执行器的 topic（用于跨进程 Chronicle 订阅）。
     */
    public String getTopic(String sinkId) {
        return "server:" + executor.getAgentId();
    }
}