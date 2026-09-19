package com.chua.deeplearning.support.onnx.nlp.translation;

/**
 * opus-mt-en-de 英译德机器翻译（marianmt，ORT 原生）。
 * <p>模型从 HuggingFace Xenova/opus-mt-en-de 自动下载（~30MB 量化 ONNX）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpusMtEnDeTranslationTranslator extends OpusMtTranslationTranslator {

    private static final String HF_BASE_URL =
            "https://huggingface.co/Xenova/opus-mt-en-de/resolve/main/onnx";

    /**
     * opusmtende翻译translator。
     */
    public OpusMtEnDeTranslationTranslator() {
        super("opus-mt-en-de", null, HF_BASE_URL);
    }
}
