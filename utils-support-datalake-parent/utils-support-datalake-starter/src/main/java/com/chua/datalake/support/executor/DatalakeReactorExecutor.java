package com.chua.datalake.support.executor;

import com.chua.common.support.concurrent.dispatcher.ConsumerDispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.chua.datalake.support.spi.sink.DataSink;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Datalake 侧执行器，继承 {@link ReactorDataSyncExecutor}。
 *
 * <p>当 DataSync 调度器把源数据 {@code List<Map<String, Object>>} 写入时：
 * <ol>
 * <li>每条 Map 被包装成 {@link DataEnvelope}（时间戳自动补全）</li>
 * <li>若注入了 {@link PipelineEngine}，调用其 execute() 执行完整管道</li>
 * <li>否则直接根据 sinkId 在本地 sinkRegistry 中派发</li>
 * </ol>
 * </p>
 *
 * <p>DataSync 调度器通过继承的 {@link ReactorDataSyncExecutor#subscribe} 和
 * {@link ReactorDataSyncExecutor#publish} 公开接口集成。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DatalakeReactorExecutor extends ReactorDataSyncExecutor {

    /**
     * 管线引擎（由 DatalakeServerBuilder 注入）
     */
    private volatile PipelineEngine pipelineEngine;

    /**
     * 直接派发用的 sink 注册表（PipelineEngine 不可用时 fallback）
     */
    private final Map<String, DataSink> sinkRegistry = new ConcurrentHashMap<>();

    /**
     * 创建 DatalakeReactorExecutor 实例
     * @param agentId agentId
     * @param boolean boolean
     */
    public DatalakeReactorExecutor(String agentId, boolean serverMode) {
        super(agentId, serverMode);
    }

    /**
     * 注入管线引擎。
     */
    public void setPipelineEngine(PipelineEngine pipelineEngine) {
        this.pipelineEngine = pipelineEngine;
    }

    /**
     * 注册 fallback 派发用的 sink。
     */
    public void registerSink(DataSink sink) {
        sinkRegistry.put(sink.type(), sink);
    }

    @Override
    /** 订阅 */
    public void subscribe(String sinkId, Consumer<List<Map<String, Object>>> consumer) {
        if (chronicleProvider == null) {
            log.warn("[datalake-server] ReactorExecutor 订阅跳过: chronicleProvider 未初始化");
            return;
        }
        String topic = "server-" + getAgentId();
        ConsumerDispatcherDefinition<List<Map<String, Object>>> definition =
                new ConsumerDispatcherDefinition<>(
                        data -> {
                            if (pipelineEngine != null) {
                                for (Map<String, Object> row : data) {
                                    try {
                                        pipelineEngine.execute(sinkId, new DataEnvelope(row));
                                    } catch (Exception e) {
                                        log.error("[datalake-server] 管线处理异常: sinkId={}, error={}", sinkId, e.getMessage(), e);
                                    }
                                }
                            }
                            if (consumer != null) {
                                consumer.accept(data);
                            }
                        },
                        List.of(topic)
                );
        chronicleProvider.subscribe(definition);
        log.info("[datalake-server] ReactorExecutor 订阅 topic={}", topic);
    }

    @Override
    /** 发布 */
    public void publish(String sinkId, List<Map<String, Object>> data) {
        if (data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map<String, Object> row : data) {
            DataEnvelope envelope = new DataEnvelope(row);
            envelope.setTimestamp(now);
            try {
                if (pipelineEngine != null) {
                    pipelineEngine.execute(sinkId, envelope);
                    continue;
                }
                DataSink sink = sinkRegistry.get(sinkId);
                if (sink != null) {
                    sink.write(envelope, new HashMap<>());
                }
            } catch (Exception e) {
                log.error("[datalake-server] 发布异常: sinkId={}, error={}", sinkId, e.getMessage(), e);
            }
        }
    }
}
