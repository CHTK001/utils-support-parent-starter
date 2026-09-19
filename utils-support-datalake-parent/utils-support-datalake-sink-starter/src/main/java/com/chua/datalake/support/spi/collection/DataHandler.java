package com.chua.datalake.support.spi.collection;

import com.chua.datalake.support.model.DataEnvelope;

/**
 * 数据处理器 SPI。
 *
 * <p>接收采集器推送的数据信封，交给数据管道（Pipeline）做后续处理。
 * 实现方通常是 PipelineManager 的适配层。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DataHandler {

    /**
     * 处理一个数据信封。
     *
     * @param pipelineId 管道标识
     * @param envelope   数据信封，不能为 null
     */
    void handle(String pipelineId, DataEnvelope envelope);
}
