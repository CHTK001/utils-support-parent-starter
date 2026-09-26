package com.chua.deeplearning.support.utils;

/**
 * 归一化或像素坐标裁剪选项。
 *
 * <p>当宽高值 <= 1.5 时视为归一化坐标，否则按像素处理。</p>
 *
 * @param imageData 图像字节
 * @param x         左边界（归一化或像素）
 * @param y         上边界（归一化或像素）
 * @param width     宽度（归一化或像素）
 * @param height    高度（归一化或像素）
 * @author CH
 * @since 4.0.0.42
 */
public record NormalizedCropOptions(
        byte[] imageData,
        float x,
        float y,
        float width,
        float height
) {

    /**
     * 规范构造器：图像字节做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。图像字节仅被解码读取，
     * 允许安全拷贝；保留其 {@code null} 语义。</p>
     *
     * @param imageData 图像字节
     */
    public NormalizedCropOptions {
        imageData = imageData == null ? null : imageData.clone();
    }

    /**
     * 访问器覆写：返回图像字节的副本。
     *
     * @return 图像字节副本；无则返回 {@code null}
     */
    @Override
    public byte[] imageData() {
        return imageData == null ? null : imageData.clone();
    }
}
