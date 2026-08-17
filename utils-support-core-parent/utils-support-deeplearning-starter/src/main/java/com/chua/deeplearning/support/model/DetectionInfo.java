package com.chua.deeplearning.support.model;

/**
 * 检测结果。
 * <p>描述单次检测的标签、置信度和位置。支持旋转框（angle != 0 时用 rw/rh 绘制旋转矩形）。</p>
 *
 * @param label      类别名称
 * @param confidence 置信度
 * @param x          左上角 x
 * @param y          左上角 y
 * @param width      宽度
 * @param height     高度
 * @param angle      旋转角度（度），0 表示正框，非 0 表示倾斜框
 * @param rw         旋转矩形宽度（angle=0 时等于 width）
 * @param rh         旋转矩形高度（angle=0 时等于 height）
 * @author CH
 * @since 4.0.0.42
 */
public record DetectionInfo(
        String label,
        float confidence,
        float x,
        float y,
        float width,
        float height,
        float angle,
        float rw,
        float rh) {

    public DetectionInfo(String label, float confidence, float x, float y, float width, float height) {
        this(label, confidence, x, y, width, height, 0f, width, height);
    }

    public DetectionInfo(String label, float confidence, float x, float y, float width, float height, float angle) {
        this(label, confidence, x, y, width, height, angle, width, height);
    }
}
