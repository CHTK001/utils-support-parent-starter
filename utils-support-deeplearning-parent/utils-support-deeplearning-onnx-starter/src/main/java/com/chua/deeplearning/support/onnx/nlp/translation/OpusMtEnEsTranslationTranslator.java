package com.chua.deeplearning.support.onnx.nlp.translation;

/**
* opus-mt-en-es 英译西班牙语机器翻译（marianmt，ORT 原生）。
* <p>模型从 HuggingFace Xenova/opus-mt-en-es 自动下载（~30MB 量化 ONNX）。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class OpusMtEnEsTranslationTranslator extends OpusMtTranslationTranslator {

    private static final String HF_BASE_URL =
            "https://huggingface.co/Xenova/opus-mt-en-es/resolve/main/onnx";

    /**
    * opusmtenes翻译translator。
     */
    public OpusMtEnEsTranslationTranslator() {
        super("opus-mt-en-es", null, HF_BASE_URL);
    }
}
