package com.chua.datalake.support.pipeline;

import com.chua.datalake.support.spi.pipeline.PipelineManager;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 PipelineManager 实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultPipelineManager implements PipelineManager {

    /**
     * 管线 DSL 存储（pipelineId → jsonDsl）
     */
    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    /** 保存Pipeline */
    public void savePipeline(String pipelineId, String jsonDsl) {
        store.put(pipelineId, jsonDsl);
    }

    @Override
    /** 获取Pipeline */
    public String getPipeline(String pipelineId) {
        return store.get(pipelineId);
    }

    @Override
    /** 删除Pipeline */
    public void deletePipeline(String pipelineId) {
        store.remove(pipelineId);
    }

    @Override
    /** PipelineIds */
    public Iterable<String> pipelineIds() {
        return Collections.unmodifiableSet(store.keySet());
    }
}