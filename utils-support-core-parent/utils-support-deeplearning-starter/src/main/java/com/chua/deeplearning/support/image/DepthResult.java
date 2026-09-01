package com.chua.deeplearning.support.image;

/**
 * 深度估计结果。
 *
 * <p>包含深度图（灰度 PNG，近处亮、远处暗）与逐像素距离矩阵（单位：米），
 * 以及最近点 / 最远点 / 中心点 / 平均距离等统计值。</p>
 *
 * @param depthImage   深度图（灰度 PNG 字节数组，尺寸与原图一致）
 * @param meters       逐像素距离矩阵（float[height][width]，单位：米，越远值越大）
 * @param minMeters    全图最近距离（米）
 * @param maxMeters    全图最远距离（米）
 * @param centerMeters 图像中心点距离（米）
 * @param meanMeters   全图平均距离（米）
 * @author CH
 * @since 4.0.0.45
 */
public record DepthResult(
        byte[] depthImage,
        float[][] meters,
        float minMeters,
        float maxMeters,
        float centerMeters,
        float meanMeters) {

    /**
     * 最近点距离。
     *
     * @return 最近距离（米）
     */
    public float nearest() {
        return minMeters;
    }

    /**
     * 最远点距离。
     *
     * @return 最远距离（米）
     */
    public float farthest() {
        return maxMeters;
    }
}
