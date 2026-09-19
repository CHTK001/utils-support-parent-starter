package com.chua.deeplearning.support.onnx.nlp.translation;

/**
 * opus-mt-en-zh 英译中机器翻译（marianmt，ORT 原生）。
 *
 * <p>嵌入式模型 jar {@code utils-support-models-onnx-opus-mt-en-zh} 提供，无需下载；
 * 资源在 {@code nlp/translation/opus_mt_en_zh/} 下。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpusMtEnZhTranslationTranslator extends OpusMtTranslationTranslator {

    /**
     * jar 内资源根目录（嵌入式）。
     */
    private static final String RESOURCE_BASE = "nlp/translation/opus_mt_en_zh/";

    /**
     * 创建 opusmtenzh翻译translator 实例
    */
    public OpusMtEnZhTranslationTranslator() {
        super("opus-mt-en-zh", RESOURCE_BASE, null);
    }
}
