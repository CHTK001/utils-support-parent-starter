package com.chua.datalake.support.model;

/**
 * 管线状态枚举。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum PipelineState {

    /** 数据已接收 */
    RECEIVED,

    /** 已过滤 */
    FILTERED,

    /** 已解析 */
    PARSED,

    /** 已清洗 */
    CLEANED,

    /** 已标准化 */
    STANDARDIZED,

    /** 落盘成功 */
    SINK_OK,

    /** 落盘失败 */
    SINK_FAIL
}