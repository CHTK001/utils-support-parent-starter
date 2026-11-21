package com.chua.deeplearning.support.onnx.ocr.entity;

/**
 * OCR
 *
 * @author CH
 * @since 4.0.0.42
 */
public record Point(double x, double y) {

    /**
     * 创建 Point 实例
     * @param x x
     * @param y y
     */
    public Point {
    }

    /**
     * 获取X
     *
     * @return 获取x的结果
     */
    public double getX() {
        return x;
    }

    /**
     * 获取Y
     *
     * @return 获取y的结果
     */
    public double getY() {
        return y;
    }
}
