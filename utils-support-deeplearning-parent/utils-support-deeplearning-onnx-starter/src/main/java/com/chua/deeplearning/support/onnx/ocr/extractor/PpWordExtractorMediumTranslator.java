package com.chua.deeplearning.support.onnx.ocr.extractor;

/**
 * PP-ocrv6 medium 文字识别（精度更高，模型体积更大）。
 *
 * <p>继承 {@link PpWordExtractorTranslator}，默认加载 medium 资源。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PpWordExtractorMediumTranslator extends PpWordExtractorTranslator {

    /**
     * 创建 ppwordextractormediumtranslator 实例
    */
    public PpWordExtractorMediumTranslator() {
        super("ocr/PP-OCRv6/medium/rec_infer/", "paddleocrv6-medium-rec");
    }
}
