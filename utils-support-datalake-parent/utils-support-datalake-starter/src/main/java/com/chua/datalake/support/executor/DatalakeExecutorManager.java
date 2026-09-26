package com.chua.datalake.support.executor;

import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.chua.datalake.support.spi.sink.DataSink;
import com.chua.starter.datasync.ExecutorManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据湖 侧的 执行器管理器 实现。每次返回同一个 数据湖reactor执行器。
 *
 * <p>{@link #start()} 会把生命周期真正下发给执行器：未注入外部分发器时，执行器会自建
 * Chronicle 通道，否则沿用宿主注入的共享分发器。{@link #stop()} 反向释放。</p>
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
     * 是否已启动，避免重复启动分发器
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

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
     * 注入管线引擎，并让执行器共享同一张 sink 注册表（用于 降级 直接派发）。
     *
     * @param unused 兼容参数
     * @param engine 管线引擎
     * @param sinks  sink 注册表
     */
    public void setPipelineEngine(ReactorDataSyncExecutor unused, PipelineEngine engine, Map<String, DataSink> sinks) {
        executor.setPipelineEngine(engine);
        executor.useSinkRegistry(sinks);
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
        if (started.compareAndSet(false, true)) {
            executor.start();
        }
        log.info("[datalake-server] ExecutorManager 启动，agentId={}", executor.getAgentId());
    }

    @Override
    /**
     * 停止
    */
    public void stop() {
        started.set(false);
        try {
            executor.stop();
        } catch (Exception e) {
            log.warn("[datalake-server] 执行器停止异常", e);
        }
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
     * <p>取值规则必须与 {@code ReactorDataSyncExecutor} 的发布端一致，
     * 否则订阅方落在一个永远无人发布的话题上。</p>
     *
     * @param sinkId sink 标识
     * @return topic 字符串
     */
    public String getTopic(String sinkId) {
        return executor.topicOf(sinkId);
    }
}
