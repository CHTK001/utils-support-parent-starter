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
}
