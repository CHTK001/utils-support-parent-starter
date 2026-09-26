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

    /**
     * 规范构造器：均值 / 标准差数组做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。两数组仅在归一化时按只读下标访问，
     * 允许安全拷贝；不归一化的调用方会显式传 {@code null}，故保留其 {@code null} 语义。</p>
     *
     * @param mean 均值，可为 {@code null}
     * @param std  标准差，可为 {@code null}
     */
    public TensorOptions {
        mean = mean == null ? null : mean.clone();
        std = std == null ? null : std.clone();
    }

    /**
     * 访问器覆写：返回均值数组的副本。
     *
     * @return 均值副本；未配置归一化则返回 {@code null}
     */
    @Override
    public float[] mean() {
        return mean == null ? null : mean.clone();
    }

    /**
     * 访问器覆写：返回标准差数组的副本。
     *
     * @return 标准差副本；未配置归一化则返回 {@code null}
     */
    @Override
    public float[] std() {
        return std == null ? null : std.clone();
    }
}
