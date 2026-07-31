package com.chua.deeplearning.support.model;

/**
 * 检测结果。
 * <p>描述单次检测的标签、置信度和位置。</p>
 *
 * @param label      类别名称
 * @param confidence 置信度
 * @param x          左上角 x
 * @param y          左上角 y
 * @param width      宽度
 * @param height     高度
 * @author CH
 * @since 4.0.0.42
 */
public record DetectionInfo(
        String label,
        float confidence,
        float x,
        float y,
        float width,
        float height) {
}
