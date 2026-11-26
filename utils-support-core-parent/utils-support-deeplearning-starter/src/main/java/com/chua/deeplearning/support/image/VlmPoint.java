package com.chua.deeplearning.support.image;

/**
 * 归一化图像坐标点（0..1，左上角为原点）。
 *
 * @param x 横坐标，0 表示左边界，1 表示右边界
 * @param y 纵坐标，0 表示上边界，1 表示下边界
 * @author CH
 * @since 4.0.0.51
 */
public record VlmPoint(double x, double y) {

    /**
     * 规范构造器：把坐标钳制到 0..1，避免模型输出的越界 token 污染下游。
     *
     * @param x 横坐标
     * @param y 纵坐标
     */
    public VlmPoint {
        x = clamp01(x);
        y = clamp01(y);
    }

    /**
     * 按像素坐标构造归一化点。
     *
     * @param x      像素横坐标
     * @param y      像素纵坐标
     * @param width  图像宽度，必须大于 0
     * @param height 图像高度，必须大于 0
     * @return 归一化点
     */
    public static VlmPoint ofPixels(double x, double y, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("图像宽高必须大于 0: " + width + "x" + height);
        }
        return new VlmPoint(x / width, y / height);
    }

    /**
     * 还原为像素坐标。
     *
     * @param width  图像宽度
     * @param height 图像高度
     * @return 长度为 2 的像素坐标数组 {@code [x, y]}
     */
    public double[] toPixels(int width, int height) {
        return new double[]{x * width, y * height};
    }

    /**
     * 钳制到 0..1。
     *
     * @param value 原值
     * @return 钳制后的值
     */
    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, value));
    }
}