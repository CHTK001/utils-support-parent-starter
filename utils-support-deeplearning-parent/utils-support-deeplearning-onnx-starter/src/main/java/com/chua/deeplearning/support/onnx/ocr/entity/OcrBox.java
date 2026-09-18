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

    /**
    * 创建 ocrbox 实例
    * @param topLeft topleft
    * @param topLeft Point
    * @param topLeft Point
    * @param topLeft Point
    * @param topRight topright
    * @param bottomRight bottomright
    * @param bottomLeft bottomleft
    */
    public OcrBox(Point topLeft, Point topRight, Point bottomRight, Point bottomLeft) {
        this.topLeft = topLeft;
        this.topRight = topRight;
        this.bottomRight = bottomRight;
        this.bottomLeft = bottomLeft;
    }

    /**
    * 获取topleft
    *
    * @return 获取topleft的结果
    */
    public Point getTopLeft() {
        return topLeft;
    }

    /**
    * 获取topright
    *
    * @return 获取topright的结果
    */
    public Point getTopRight() {
        return topRight;
    }

    /**
    * 获取bottomright
    *
    * @return 获取bottomright的结果
    */
    public Point getBottomRight() {
        return bottomRight;
    }

    /**
    * 获取bottomleft
    *
    * @return 获取bottomleft的结果
    */
    public Point getBottomLeft() {
        return bottomLeft;
    }
}
