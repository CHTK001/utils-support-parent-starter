package com.chua.deeplearning.support.onnx.ocr.entity;

/**
 * OCR       
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Point {

    /** X 坐标 */
    private final double x;
    /** Y 坐标 */
    private final double y;

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }
}
