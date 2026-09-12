package com.chua.datalake.support.spi.pipeline;

import com.chua.datalake.support.model.DataEnvelope;

/**
 * 管线执行引擎 SPI。
 *
 * <p>调用方调用 {@link #execute(String, DataEnvelope)} 后，引擎应当：
 * <ol>
 *   <li>从 {@link PipelineManager} 获取 DSL JSON</li>
 *   <li>解析 DSL 为 {@link PipelineConfig}</li>
 *   <li>按阶段顺序执行：Filter → Parser → Cleaner → Standardizer → Sink</li>
 *   <li>每个阶段处理后将结果写入 envelope</li>
 * </ol>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface PipelineEngine {

    /**
      * 以管线 标识 执行一条数据
     *
     * @param pipelineId 管线标识
     * @param envelope   待处理的数据信封
     */
    void execute(String pipelineId, DataEnvelope envelope);
}