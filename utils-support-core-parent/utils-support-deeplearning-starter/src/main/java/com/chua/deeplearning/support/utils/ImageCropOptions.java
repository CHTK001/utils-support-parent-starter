package com.chua.deeplearning.support.utils;

/**
 * 像素矩形裁剪选项。
 *
 * @param imageData 图像字节
 * @param x         左边界
 * @param y         上边界
 * @param width     宽度
 * @param height    高度
 * @author CH
 * @since 4.0.0.42
 */
public record ImageCropOptions(
        byte[] imageData,
        int x,
        int y,
        int width,
        int height
) {
}
