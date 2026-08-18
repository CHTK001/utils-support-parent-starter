package com.chua.deeplearning.support.onnx.ocr.entity;

/**
 * OCR         
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OcrBox {

    /** 左上角点 */
    /** 顶部左侧 */
    private final Point topLeft;
    /** 右上角点 */
    /** 顶部右侧 */
    private final Point topRight;
    /** 右下角点 */
    /** 底部右侧 */
    private final Point bottomRight;
    /** 左下角点 */
    /** 底部左侧 */
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
