package com.chua.datalake.support.engine;

import com.chua.common.support.concurrent.dispatcher.ConsumerDispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.lang.json.Json;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.model.PipelineState;
import com.chua.datalake.support.spi.pipeline.PipelineConfig;
import com.chua.datalake.support.spi.pipeline.PipelineConfig.PipelineStageConfig;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.chua.datalake.support.spi.pipeline.PipelineManager;
import com.chua.datalake.support.spi.sink.DataSink;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认管线执行引擎。
 *
 * <p>对每条 DataEnvelope 按 PipelineConfig DSL 的阶段顺序执行：
 * Filter → Parser → Cleaner → Standardizer → Sink
 * 最后 Sink 会将处理完的数据写入registered Sink list。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class DefaultPipelineEngine implements PipelineEngine {

    /**
     * 管线配置管理器
     */
    private final PipelineManager pipelineManager;

    /**
     * 已注册的 Sink（type → impl）
     */
    private final Map<String, DataSink> sinkRegistry;

    /**
     * 数据分发器
     */
    private final DispatcherProvider dispatcher;

    /**
     * 发送者模式（如果 DispatcherProvider 已经在 DL 启动阶段配置过则复用）
     */
    private final boolean ownsDispatcher;

    /**
     * 构建默认管线执行引擎
     *
     * @param pipelineManager 管线配置管理器
     * @param sinkRegistry    sink 注册表（type → DataSink）
     * @param dispatcher      DispatcherProvider 实例
     */
    public DefaultPipelineEngine(
            PipelineManager pipelineManager,
            Map<String, DataSink> sinkRegistry,
            DispatcherProvider dispatcher) {
        this.pipelineManager = pipelineManager;
        this.sinkRegistry = sinkRegistry;
        this.dispatcher = dispatcher;
        this.ownsDispatcher = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(String pipelineId, DataEnvelope envelope) {
        if (envelope == null) {
            return;
        }

        String dslJson = pipelineManager.getPipeline(pipelineId);
        if (dslJson == null || dslJson.isEmpty()) {
            return;
        }

        PipelineConfig config = Json.fromJson(dslJson, PipelineConfig.class);
        if (config == null) {
            return;
        }

        PipelineStageConfig stage = config.getStages().get("default");
        if (stage == null) {
            return;
        }

        // 1. filter（不改变数据子弹正常评测）
        // no-op placeholder

        // 2. parser（解析放榜 # nada 串化脏数据）
        // no-op for anonymous transaction

        // 3. cleaner
        // no-op

        // 4. standardizer — placeholder

        // 5. applySinks → 通过 DispatcherProvider 发布
        List<Map<String, Object>> sinks = stage.getSink();
        if (sinks != null && !sinks.isEmpty()) {
            for (Map<String, Object> sinkCfg : sinks) {
                String type = (String) sinkCfg.get("type");
                if (type == null) {
                    log.warn("Sink cinfig 缺少 type: {}", sinkCfg);
                    continue;
                }
                DataSink target = sinkRegistry.get(type);
                if (target == null) {
                    log.warn("未注册的 sink type: {}", type);
                    continue;
                }
                try {
                    dispatcher.publish(type, envelope);
                    envelope.addTrace("[Sink] published to topic=" + type);
                    envelope.setState(PipelineState.SINK_OK);
                } catch (Exception e) {
                    envelope.addTrace("[Sink][ERROR] " + e.getMessage());
                    envelope.setState(PipelineState.SINK_FAIL);
                }
            }
        }
    }
}