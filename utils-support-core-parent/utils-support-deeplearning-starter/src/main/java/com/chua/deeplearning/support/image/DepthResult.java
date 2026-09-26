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
     * 规范构造器：数组组件做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。距离矩阵是二维数组，仅复制外层
     * 引用无法阻止调用方改写内层行，故逐行 深拷贝。</p>
     *
     * <p>两个组件都保留 {@code null} 语义：无输出时为 {@code null}。</p>
     *
     * @param depthImage 深度图字节
     * @param meters     逐像素距离矩阵
     */
    public DepthResult {
        depthImage = depthImage == null ? null : depthImage.clone();
        meters = cloneMatrix(meters);
    }

    /**
     * 访问器覆写：返回深度图字节的副本。
     *
     * @return 深度图字节副本；无则返回 {@code null}
     */
    @Override
    public byte[] depthImage() {
        return depthImage == null ? null : depthImage.clone();
    }

    /**
     * 访问器覆写：返回距离矩阵的 深拷贝。
     *
     * @return 距离矩阵副本；无则返回 {@code null}
     */
    @Override
    public float[][] meters() {
        return cloneMatrix(meters);
    }

    /**
     * 逐行 深拷贝 二维浮点矩阵。
     *
     * @param src 源矩阵，可为 {@code null}
     * @return 逐行拷贝后的矩阵；源为 {@code null} 时返回 {@code null}
     */
    private static float[][] cloneMatrix(float[][] src) {
        if (src == null) {
            return null;
        }
        float[][] out = new float[src.length][];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i] == null ? null : src[i].clone();
        }
        return out;
    }

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
