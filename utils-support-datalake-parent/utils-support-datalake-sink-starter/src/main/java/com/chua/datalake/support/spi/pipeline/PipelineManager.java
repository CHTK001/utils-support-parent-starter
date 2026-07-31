package com.chua.datalake.support.spi.pipeline;

/**
 * 管线配置管理 SPI，负责管线配置的持久化和查询。
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface PipelineManager {

    /**
     * 保存/更新管线配置
     *
     * @param pipelineId 管线唯一标识
     * @param jsonDsl    管线 JSON‑DSL 字符串
     */
    void savePipeline(String pipelineId, String jsonDsl);

    /**
     * 获取管线 JSON‑DSL 字符串
     *
     * @param pipelineId 管线标识
     * @return DSL 字符串，不存在返回 null
     */
    String getPipeline(String pipelineId);

    /**
     * 删除管线
     *
     * @param pipelineId 管线标识
     */
    void deletePipeline(String pipelineId);

    /**
     * 获取所有已注册管线 ID
     *
     * @return 管线 ID 列表（不可变）
     */
    Iterable<String> pipelineIds();
}