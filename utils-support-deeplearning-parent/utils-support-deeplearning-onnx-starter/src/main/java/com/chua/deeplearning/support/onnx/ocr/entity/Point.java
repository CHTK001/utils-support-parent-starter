package com.chua.deeplearning.support.onnx.ocr.entity;

/**
 * OCR       
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Point {

    /** X 坐标 */
    /** X坐标 */
    private final double x;
    /** Y 坐标 */
    /** Y坐标 */
    private final double y;

    /**
     * 创建 Point 实例
     * @param x x
     * @param double double
     */
    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /** 获取X */
    public double getX() {
        return x;
    }

    /** 获取Y */
    public double getY() {
        return y;
    }
}
