package com.chua.deeplearning.support.model;

/**
* 检测结果。
* <p>描述单次检测的标签、置信度和位置。支持旋转框（angle != 0 时用 rw/rh/cx/cy 绘制旋转矩形）。</p>
*
* @param label      类别名称
* @param confidence 置信度
* @param x          轴对齐框左上角 x
* @param y          轴对齐框左上角 y
* @param width      轴对齐框宽度
* @param height     轴对齐框高度
* @param angle      旋转角度（度），0 表示正框
* @param rw         旋转矩形宽度
* @param rh         旋转矩形高度
* @param cx         旋转矩形中心 x
* @param cy         旋转矩形中心 y
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
        float rh,
        float cx,
        float cy) {

    /**
    * 构造轴对齐检测框（无旋转角度），自动计算中心坐标和旋转框宽高。
    *
    * @param label      标签
    * @param confidence 置信度
    * @param x          左上角 x
    * @param y          左上角 y
    * @param width      宽度
    * @param height     高度
    * @return detection信息的结果
    */
    public DetectionInfo(String label, float confidence, float x, float y, float width, float height) {
        this(label, confidence, x, y, width, height, 0f, width, height, x + width / 2f, y + height / 2f);
    }

    /**
    * 构造旋转检测框，自动计算旋转框宽高和中心坐标。
    *
    * @param label      标签
    * @param confidence 置信度
    * @param x          左上角 x
    * @param y          左上角 y
    * @param width      宽度
    * @param height     高度
    * @param angle      旋转角度（度）
    * @return detection信息的结果
    */
    public DetectionInfo(String label, float confidence, float x, float y, float width, float height, float angle) {
        this(label, confidence, x, y, width, height, angle, width, height, x + width / 2f, y + height / 2f);
    }
}
