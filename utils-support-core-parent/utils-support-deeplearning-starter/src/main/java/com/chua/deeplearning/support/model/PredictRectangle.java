package com.chua.deeplearning.support.model;

/**
 * 预测区域。
 * <p>描述检测到的目标位置和置信度信息。</p>
 *
 * @param x           左上角 x 坐标
 * @param y           左上角 y 坐标
 * @param width       区域宽度
 * @param height      区域高度
 * @param confidence  置信度 0~1
 * @param label       类别编号
 * @param labelName   类别名称
 * @param keypoints   关键点列表（像素坐标），可为空；每项 [x, y]
 * @author CH
 * @since 4.0.0.42
 */
public record PredictRectangle(
        float x,
        float y,
        float width,
        float height,
        float confidence,
        int label,
        String labelName,
        java.util.List<float[]> keypoints) {

    /**
     * 规范构造器：关键点列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。关键点列表的元素本身是可变数组，
     * {@code List.copyOf} 只冻结外层引用，故逐项 拷贝 元素数组。</p>
     *
     * <p>刻意保留 {@code null} 语义：关键点模型未配置或该目标无关键点时为 {@code null}，
     * {@code FacePipeline} 与 {@code ImageUtils} 均显式判空后按“关键点不足”降级。</p>
     *
     * @param keypoints 关键点列表
     */
    public PredictRectangle {
        keypoints = keypoints == null ? null
                : keypoints.stream().map(point -> point == null ? null : point.clone()).toList();
    }

    /**
     * 兼容无关键点的构造。
     * @param x x
     * @param y y
     * @param width width
     * @param height height
     * @param confidence 信心
     * @param label 标签
     * @param labelName 标签名称
     * @return PredictRectangle的结果
     */
    public PredictRectangle(float x, float y, float width, float height, float confidence, int label, String labelName) {
        this(x, y, width, height, confidence, label, labelName, java.util.List.of());
    }
}
