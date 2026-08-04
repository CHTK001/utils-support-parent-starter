package com.chua.common.support.ai.probe;

import lombok.Builder;
import org.jspecify.annotations.NullUnmarked;

/**
 * 单个探测维度结果。
 *
 * @param dimension 探测维度
 * @param passed 是否通过该维度探测
 * @param confidence 置信度（0.0 ~ 1.0）
 * @param detail 详细描述
 * @param rawData 原始数据（如响应文本、HTTP 头等）
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@Builder
public record ProbeResult(
    ProbeDimension dimension,
    boolean passed,
    double confidence,
    String detail,
    String rawData
) {}
