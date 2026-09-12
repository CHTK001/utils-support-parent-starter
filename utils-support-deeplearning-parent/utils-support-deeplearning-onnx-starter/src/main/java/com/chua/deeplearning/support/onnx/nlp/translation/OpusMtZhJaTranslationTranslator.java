package com.chua.deeplearning.support.onnx.nlp.translation;

/**
   * opus-mt-zh-ja 中译日机器翻译（marianmt，ORT 原生）。
 * <p>模型从 HuggingFace Xenova/opus-mt-zh-ja 自动下载（~30MB 量化 ONNX）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpusMtZhJaTranslationTranslator extends OpusMtTranslationTranslator {

    private static final String HF_BASE_URL =
            "https://huggingface.co/Xenova/opus-mt-zh-ja/resolve/main/onnx";

    /**
     * opusmtzhja翻译translator。
     */
    public OpusMtZhJaTranslationTranslator() {
        super("opus-mt-zh-ja", null, HF_BASE_URL);
    }
}
