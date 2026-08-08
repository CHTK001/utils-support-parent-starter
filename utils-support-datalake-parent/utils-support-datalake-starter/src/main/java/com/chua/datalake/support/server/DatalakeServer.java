package com.chua.datalake.support.server;

import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.concurrent.offset.OffsetFlow;
import com.chua.common.support.network.server.Server;
import com.chua.datalake.support.manager.SinkManager;
import com.chua.datalake.support.manager.SubscriberManager;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.chua.datalake.support.spi.pipeline.PipelineManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DatalakeServer — 数据中台控制中心。
 *
 * <p>维护 Pipeline 引擎、SinkManager、SubscriberManager、Dispatcher 及 ApiServer。
 * 启动时统一拉起所有服务：Datasync（可选）、管线管理、sink 及其订阅器和对外 API。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class DatalakeServer {

    /**
     * 管线配置管理器
     */
    private final PipelineManager pipelineManager;

    /**
     * 管线执行引擎
     */
    private final PipelineEngine pipelineEngine;

    /**
     * 数据分发器
     */
    private final DispatcherProvider dispatcher;

    /**
     * Sink 管理器
     */
    private final SinkManager sinkManager;

    /**
     * 订阅管理器
     */
    private final SubscriberManager subscriberManager;

    /**
     * Offset 门面
     */
    private final OffsetFlow offsetFlow;

    /**
     * API 服务器
     */
    private final Server apiServer;

    /**
     * 运行状态
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DatalakeServer(
            PipelineManager pipelineManager,
            PipelineEngine pipelineEngine,
            DispatcherProvider dispatcher,
            SinkManager sinkManager,
            SubscriberManager subscriberManager,
            OffsetFlow offsetFlow,
            Server apiServer) {
        this.pipelineManager = pipelineManager;
        this.pipelineEngine = pipelineEngine;
        this.dispatcher = dispatcher;
        this.sinkManager = sinkManager;
        this.subscriberManager = subscriberManager;
        this.offsetFlow = offsetFlow;
        this.apiServer = apiServer;
    }

    /**
     * 启动 Datalake 服务
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        log.info("[datalake-server] DatalakeServer 启动中");
        sinkManager.start();
        subscriberManager.start();
        apiServer.start();
        log.info("[datalake-server] DatalakeServer 启动完成");
    }

    /**
     * 停止 Datalake 服务
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("[datalake-server] DatalakeServer 停止中");
        apiServer.stop();
        subscriberManager.stop();
        sinkManager.stop();
        if (dispatcher != null) {
            dispatcher.close();
        }
        if (offsetFlow != null) {
            offsetFlow.close();
        }
        log.info("[datalake-server] DatalakeServer 已停止");
    }

    /**
     * 返回 PipelineManager
     */
    public PipelineManager getPipelineManager() {
        return pipelineManager;
    }

    /**
     * 返回 PipelineEngine
     */
    public PipelineEngine getPipelineEngine() {
        return pipelineEngine;
    }
}