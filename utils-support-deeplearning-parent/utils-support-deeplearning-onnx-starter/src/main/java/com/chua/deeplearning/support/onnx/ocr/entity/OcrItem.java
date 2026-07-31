package com.chua.deeplearning.support.onnx.ocr.entity;

/**
 * OCR         
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OcrItem {

    private OcrBox ocrBox;
    private float score;

    public OcrBox getOcrBox() {
        return ocrBox;
    }

    public void setOcrBox(OcrBox ocrBox) {
        this.ocrBox = ocrBox;
    }

    public float getScore() {
        return score;
    }

    public void setScore(float score) {
        this.score = score;
    }
}
