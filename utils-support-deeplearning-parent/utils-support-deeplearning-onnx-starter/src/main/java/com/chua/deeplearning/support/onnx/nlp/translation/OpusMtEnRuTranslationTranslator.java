package com.chua.deeplearning.support.onnx.nlp.translation;

/**
 * opus-mt-en-ru 英译俄机器翻译（MarianMT，ORT 原生）。
 * <p>模型从 HuggingFace Xenova/opus-mt-en-ru 自动下载（~30MB 量化 ONNX）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpusMtEnRuTranslationTranslator extends OpusMtTranslationTranslator {

    private static final String HF_BASE_URL =
            "https://huggingface.co/Xenova/opus-mt-en-ru/resolve/main/onnx";

    public OpusMtEnRuTranslationTranslator() {
        super("opus-mt-en-ru", null, HF_BASE_URL);
    }
}
