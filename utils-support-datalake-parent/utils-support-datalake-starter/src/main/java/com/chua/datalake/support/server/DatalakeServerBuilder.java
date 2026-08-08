package com.chua.datalake.support.server;

import com.chua.chronicle.support.dispatcher.ChronicleDispatcherProvider;
import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.concurrent.offset.OffsetFlow;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkHttpServer;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.datalake.support.engine.DefaultPipelineEngine;
import com.chua.datalake.support.executor.DatalakeExecutorManager;
import com.chua.datalake.support.manager.SinkManager;
import com.chua.datalake.support.manager.SubscriberManager;
import com.chua.datalake.support.pipeline.DefaultPipelineManager;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.chua.datalake.support.spi.pipeline.PipelineManager;
import com.chua.datalake.support.spi.sink.DataSink;
import com.chua.starter.datasync.DataSyncServer;
import com.chua.starter.datasync.DefaultDataSyncServer;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DatalakeServer 构建器。
 *
 * <p>对外提供链式构建入口，统一注入依赖项。一旦 {@link #dataSyncServer(DataSyncServer)}
 * 被调用，构建时会自动把 DataSync 调度器的 ExecutorManager 替换为 DatalakeExecutorManager。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class DatalakeServerBuilder {

    /**
     * 管线配置管理器
     */
    private PipelineManager pipelineManager;

    /**
     * sink 注册表
     */
    private final Map<String, DataSink> sinkRegistry = new ConcurrentHashMap<>();

    /**
     * 外部注入的 DataSyncServer
     */
    private DataSyncServer dataSyncServer;

    /**
     * DispatcherProvider 实例
     */
    private DispatcherProvider dispatcher;

    /**
     * Chronicle 共享目录（用于跨进程通信）
     */
    private String dataPath;

    /**
     * offset 门面
     */
    private OffsetFlow offsetFlow;

    /**
     * 启动期注入的 API Server
     */
    private Server apiServer;

    /**
     * 私有构造，强制使用 {@link #builder()} 创建。
     */
    private DatalakeServerBuilder() {
    }

    /**
     * 创建构建器实例
     *
     * @return 新构建器
     */
    public static DatalakeServerBuilder builder() {
        return new DatalakeServerBuilder();
    }

    /**
     * 设置管线管理器
     *
     * @param pipelineManager 管线管理器实例
     * @return 当前构建器
     */
    public DatalakeServerBuilder pipelineManager(PipelineManager pipelineManager) {
        this.pipelineManager = pipelineManager;
        return this;
    }

    /**
     * 注册单个 sink
     *
     * @param sink 待注册的 sink
     * @return 当前构建器
     */
    public DatalakeServerBuilder registerSink(DataSink sink) {
        this.sinkRegistry.put(sink.type(), sink);
        return this;
    }

    /**
     * 批量注册 sink
     *
     * @param sinks sink 集合
     * @return 当前构建器
     */
    public DatalakeServerBuilder sinks(Map<String, DataSink> sinks) {
        this.sinkRegistry.putAll(sinks);
        return this;
    }

    /**
     * 注入 DataSyncServer
     *
     * @param dataSyncServer DataSyncServer 实例
     * @return 当前构建器
     */
    public DatalakeServerBuilder dataSyncServer(DataSyncServer dataSyncServer) {
        this.dataSyncServer = dataSyncServer;
        return this;
    }

    /**
     * 设置 Chronicle 共享目录
     *
     * @param dataPath 数据路径
     * @return 当前构建器
     */
    public DatalakeServerBuilder dataPath(String dataPath) {
        this.dataPath = dataPath;
        return this;
    }

    /**
     * 注入自定义 API Server
     *
     * @param apiServer API Server 实例
     * @return 当前构建器
     */
    public DatalakeServerBuilder apiServer(Server apiServer) {
        this.apiServer = apiServer;
        return this;
    }

    /**
     * 构建 DatalakeServer 实例
     *
     * @return 已配置的服务器
     */
    public DatalakeServer build() {
        if (pipelineManager == null) {
            pipelineManager = new DefaultPipelineManager();
        }
        if (dispatcher == null) {
            String path = dataPath != null ? dataPath : "datalake-dispatch";
            DispatcherConfig cfg = DispatcherConfig.builder().dataPath(path).build();
            dispatcher = new ChronicleDispatcherProvider(cfg);
        }
        dispatcher.start();
        if (offsetFlow == null) {
            offsetFlow = OffsetFlow.create().start();
        }

        PipelineEngine pipelineEngine = new DefaultPipelineEngine(
                pipelineManager,
                sinkRegistry,
                dispatcher);

        SinkManager sinkManager = new SinkManager(sinkRegistry);
        SubscriberManager subscriberManager = new SubscriberManager();

        if (dataSyncServer instanceof DefaultDataSyncServer) {
            DefaultDataSyncServer defaultServer = (DefaultDataSyncServer) dataSyncServer;
            DatalakeExecutorManager execMgr = new DatalakeExecutorManager();
            execMgr.setPipelineEngine(null, pipelineEngine, sinkRegistry);
            execMgr.setDispatcherProvider(dispatcher);
            defaultServer.setExecutorManager(execMgr);
            log.info("[datalake-server] 已注入 DatalakeExecutorManager 到 DataSyncServer，共享 DispatcherProvider");
        }

        ServiceProvider<DataSink> sinkProvider = ServiceProvider.of(DataSink.class);
        for (String ext : sinkProvider.getExtensions()) {
            DataSink sink = sinkProvider.getNewExtension(ext);
            if (sink != null) {
                sinkRegistry.putIfAbsent(sink.type(), sink);
            }
        }

        if (apiServer == null) {
            ServerSetting setting = ServerSetting.defaults();
            setting.setPort(8700);
            apiServer = new JdkHttpServer(setting);
            apiServer.setObjectContext(com.chua.common.support.objects.DefaultObjectContext.create());
        }

        return new DatalakeServer(
                pipelineManager,
                pipelineEngine,
                dispatcher,
                sinkManager,
                subscriberManager,
                offsetFlow,
                apiServer);
    }
}