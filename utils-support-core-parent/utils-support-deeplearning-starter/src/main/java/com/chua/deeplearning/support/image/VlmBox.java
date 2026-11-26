package com.chua.deeplearning.support.image;

/**
 * 归一化图像检测框（0..1，左上角为原点）。
 *
 * <p>与模型输出 token（1/1000 归一化坐标）保持同一语义：{@code x2 > x1}、{@code y2 > y1}
 * 时才视为有效框；模型给出的退化框（零宽/零高、反向）由 {@link #isValid()} 判定后丢弃，
 * 不在构造期静默改写，避免把「模型没检到」伪装成「检到一个空框」。
 *
 * @param x1 左上角横坐标
 * @param y1 左上角纵坐标
 * @param x2 右下角横坐标
 * @param y2 右下角纵坐标
 * @author CH
 * @since 4.0.0.51
 */
public record VlmBox(double x1, double y1, double x2, double y2) {

    /**
     * 规范构造器：坐标钳制到 0..1，反向框保持原样交给 {@link #isValid()} 判定。
     *
     * @param x1 左上角横坐标
     * @param y1 左上角纵坐标
     * @param x2 右下角横坐标
     * @param y2 右下角纵坐标
     */
    public VlmBox {
        x1 = clamp01(x1);
        y1 = clamp01(y1);
        x2 = clamp01(x2);
        y2 = clamp01(y2);
    }

    /**
     * 按像素坐标构造归一化框。
     *
     * @param x1     左上角像素横坐标
     * @param y1     左上角像素纵坐标
     * @param x2     右下角像素横坐标
     * @param y2     右下角像素纵坐标
     * @param width  图像宽度，必须大于 0
     * @param height 图像高度，必须大于 0
     * @return 归一化框
     */
    public static VlmBox ofPixels(double x1, double y1, double x2, double y2, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("图像宽高必须大于 0: " + width + "x" + height);
        }
        return new VlmBox(x1 / width, y1 / height, x2 / width, y2 / height);
    }

    /**
     * 框是否有效（正面积）。
     *
     * @return 有效返回 true
     */
    public boolean isValid() {
        return x2 > x1 && y2 > y1;
    }

    /**
     * 框宽（归一化）。
     *
     * @return 宽度，无效框返回非正值
     */
    public double width() {
        return x2 - x1;
    }

    /**
     * 框高（归一化）。
     *
     * @return 高度，无效框返回非正值
     */
    public double height() {
        return y2 - y1;
    }

    /**
     * 框面积（归一化平方）。
     *
     * @return 面积
     */
    public double area() {
        return Math.max(0D, width()) * Math.max(0D, height());
    }

    /**
     * 还原为像素坐标。
     *
     * @param width  图像宽度
     * @param height 图像高度
     * @return 长度为 4 的像素坐标数组 {@code [x1, y1, x2, y2]}
     */
    public double[] toPixels(int width, int height) {
        return new double[]{x1 * width, y1 * height, x2 * width, y2 * height};
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