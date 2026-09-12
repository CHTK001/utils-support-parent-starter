package com.chua.deeplearning.support.model;

import java.util.List;

/**
* 视频动作检测结果。
* <p>描述视频中某个时间点发生的动作检测结果，包含时间戳、动作类别、置信度和空间位置。</p>
*
* @param timestamp  时间戳（秒），表示动作发生的时刻
* @param label      动作类别名称
* @param confidence 置信度 0~1
* @param x          检测框左上角 x
* @param y          检测框左上角 y
* @param width      检测框宽度
* @param height     检测框高度
* @author CH
* @since 4.0.0.42
 */
public record ActionDetectionResult(
        float timestamp,
        String label,
        float confidence,
        float x,
        float y,
        float width,
        float height) {
}