package com.chua.deeplearning.support.ocr;

import com.chua.deeplearning.support.model.PredictRectangle;

/**
 * OCR 识别结果。
 * <p>包含单个文本块的识别文本、置信度和位置信息。</p>
 *
 * @param text         识别文本
 * @param confidence   置信度
 * @param boundingBox  文本区域
 * @author CH
 * @since 4.0.0.42
 */
public record OcrResult(
        String text,
        float confidence,
        PredictRectangle boundingBox) {
}
