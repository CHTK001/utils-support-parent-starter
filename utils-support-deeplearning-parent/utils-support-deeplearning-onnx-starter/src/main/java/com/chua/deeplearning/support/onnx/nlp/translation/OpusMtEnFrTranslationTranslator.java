package com.chua.deeplearning.support.onnx.nlp.translation;

/**
 * opus-mt-en-fr 英译法机器翻译（MarianMT，ORT 原生）。
 * <p>模型从 HuggingFace Xenova/opus-mt-en-fr 自动下载（~30MB 量化 ONNX）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpusMtEnFrTranslationTranslator extends OpusMtTranslationTranslator {

    private static final String HF_BASE_URL =
            "https://huggingface.co/Xenova/opus-mt-en-fr/resolve/main/onnx";

    public OpusMtEnFrTranslationTranslator() {
        super("opus-mt-en-fr", null, HF_BASE_URL);
    }
}
