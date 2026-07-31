package com.chua.datalake.support.model;

/**
 * 管线状态枚举。
 *
 * @author CH
 * @since 4.0.0.43
 */
public enum PipelineState {

    FILTERED,

    PARSED,

    CLEANED,

    STANDARDIZED,

    SINK_OK,

    SINK_FAIL
}