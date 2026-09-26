package com.chua.deeplearning.support.plate;

import com.chua.deeplearning.support.model.PredictRectangle;

/**
 * 车牌检测命中结果，包含边界框、裁剪图像和识别文字。
 *
 * @param box        检测边界框
 * @param plateImage 车牌裁剪图像
 * @param plateText  识别出的车牌号码
 * @param plateColor 识别出的车牌颜色
 * @author CH
 * @since 4.0.0.42
 */
public record PlateDetectHit(
        PredictRectangle box,
        byte[] plateImage,
        String plateText,
        String plateColor) {

    /**
     * 规范构造器：数组组件做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。裁剪失败时组件可能为
     * {@code null}，保留其 {@code null} 语义。</p>
     *
     * @param plateImage 车牌裁剪图像
     */
    public PlateDetectHit {
        plateImage = plateImage == null ? null : plateImage.clone();
    }

    /**
     * 访问器覆写：返回车牌裁剪图像的副本。
     *
     * @return 车牌裁剪图像副本；无则返回 {@code null}
     */
    @Override
    public byte[] plateImage() {
        return plateImage == null ? null : plateImage.clone();
    }
}