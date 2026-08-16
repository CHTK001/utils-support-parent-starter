package com.chua.deeplearning.support.onnx.ocr.extractor;

/**
 * PP-OCRv6 medium 文字检测（精度更高，模型体积更大）。
 *
 * <p>继承 {@link PpOcrDetTranslator}，默认加载 medium 资源。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PpOcrDetMediumTranslator extends PpOcrDetTranslator {

    public PpOcrDetMediumTranslator() {
        super("ocr/PP-OCRv6/medium/det_infer/", "paddleocrv6-medium-det");
    }
}
