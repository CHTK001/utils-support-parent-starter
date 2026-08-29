package com.chua.deeplearning.support.onnx.nlp.translation;

/**
 * opus-mt-en-zh 英译中机器翻译（MarianMT，ORT 原生）。
<<<<<<< Updated upstream
 *
 * <p>嵌入式模型 jar {@code utils-support-models-onnx-opus-mt-en-zh} 提供，无需下载；
 * 资源在 {@code nlp/translation/opus_mt_en_zh/} 下。</p>
=======
 * <p>模型从 HuggingFace Xenova/opus-mt-en-zh 自动下载（~30MB 量化 ONNX）。</p>
>>>>>>> Stashed changes
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpusMtEnZhTranslationTranslator extends OpusMtTranslationTranslator {

<<<<<<< Updated upstream
    /**
     * jar 内资源根目录（嵌入式）。
     */
    private static final String RESOURCE_BASE = "nlp/translation/opus_mt_en_zh/";

    /** 创建 OpusMtEnZhTranslationTranslator 实例 */
    public OpusMtEnZhTranslationTranslator() {
        super("opus-mt-en-zh", RESOURCE_BASE, null);
=======
    /** HF 模型仓库 onnx 目录 URL */
    private static final String HF_BASE_URL =
            "https://huggingface.co/Xenova/opus-mt-en-zh/resolve/main/onnx";

    public OpusMtEnZhTranslationTranslator() {
        super("opus-mt-en-zh", null, HF_BASE_URL);
>>>>>>> Stashed changes
    }
}
