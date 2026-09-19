package com.chua.deeplearning.support.onnx.ocr.entity;

/**
 * OCR         
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OcrItem {

    /** OCR 文本框 */
    /** OCRBOX */
    private OcrBox ocrBox;
    /** 得分 */
    /** 分数 */
    private float score;

    /**
     * 获取ocrbox
     *
     * @return 获取ocrbox的结果
     */
    public OcrBox getOcrBox() {
        return ocrBox;
    }

    /**
     * 设置ocrbox
     *
     * @param ocrBox ocrbox
     */
    public void setOcrBox(OcrBox ocrBox) {
        this.ocrBox = ocrBox;
    }

    /**
     * 获取Score
     *
     * @return 获取score的结果
     */
    public float getScore() {
        return score;
    }

    /**
     * 设置Score
     *
     * @param score score
     */
    public void setScore(float score) {
        this.score = score;
    }
}
