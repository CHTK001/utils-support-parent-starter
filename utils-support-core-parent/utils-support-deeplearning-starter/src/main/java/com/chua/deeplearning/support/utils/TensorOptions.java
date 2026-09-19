package com.chua.deeplearning.support.utils;

import ai.djl.modality.cv.Image;

/**
 * 张量转换选项。
 *
 * @param image      DJL 图像
 * @param size       目标尺寸（正方形边长）
 * @param mean       均值（可为 空）
 * @param std        标准差（可为 空）
 * @param centerCrop true 短边缩放+中心裁剪；false 直接拉伸
 * @author CH
 * @since 4.0.0.42
 */
public record TensorOptions(
        Image image,
        int size,
        float[] mean,
        float[] std,
        boolean centerCrop
) {
}
