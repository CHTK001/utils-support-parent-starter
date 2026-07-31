package com.chua.deeplearning.support.onnx.ocr.entity;

/**
 * OCR         
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OcrBox {

    private final Point topLeft;
    private final Point topRight;
    private final Point bottomRight;
    private final Point bottomLeft;

    public OcrBox(Point topLeft, Point topRight, Point bottomRight, Point bottomLeft) {
        this.topLeft = topLeft;
        this.topRight = topRight;
        this.bottomRight = bottomRight;
        this.bottomLeft = bottomLeft;
    }

    public Point getTopLeft() {
        return topLeft;
    }

    public Point getTopRight() {
        return topRight;
    }

    public Point getBottomRight() {
        return bottomRight;
    }

    public Point getBottomLeft() {
        return bottomLeft;
    }
}
