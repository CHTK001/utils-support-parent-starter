package com.chua.deeplearning.support.onnx.nlp.translation;

/**
 * opus-mt-en-zh 英译中机器翻译（MarianMT，ORT 原生）。
 * <p>模型从 HuggingFace Xenova/opus-mt-en-zh 自动下载（~30MB 量化 ONNX）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpusMtEnZhTranslationTranslator extends OpusMtTranslationTranslator {

    /** HF 模型仓库 onnx 目录 URL */
    private static final String HF_BASE_URL =
            "https://huggingface.co/Xenova/opus-mt-en-zh/resolve/main/onnx";

    public OpusMtEnZhTranslationTranslator() {
        super("opus-mt-en-zh", null, HF_BASE_URL);
    }
}
