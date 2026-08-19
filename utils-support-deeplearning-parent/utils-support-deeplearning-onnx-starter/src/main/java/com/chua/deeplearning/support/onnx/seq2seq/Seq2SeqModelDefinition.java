package com.chua.deeplearning.support.onnx.seq2seq;

import java.util.List;

/**
 * Seq2Seq 模型定义。
 * <p>描述一个 encoder-decoder 自回归模型的资源布局与解码器架构参数：
 * classpath 嵌入式资源路径（优先）、modelscope 下载文件清单（备用）、
 * 层数 / 注意力头数 / 单头维度 / EOS / decoder 起始 token。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public record Seq2SeqModelDefinition(

        /**
         * 模型标识，同时作为本地缓存目录名。
         */
        String modelId,

        /**
         * classpath 嵌入式资源根路径（目录，以 / 结尾），为空表示无嵌入式模型。
         */
        String classpathBase,

        /**
         * modelscope 模型仓库名，如 Xenova/t5-small。
         */
        String modelscopeRepo,

        /**
         * modelscope 下载文件清单（相对仓库根路径，含 onnx/ 前缀）。
         */
        List<String> downloadFiles,

        /**
         * 缓存/嵌入式目录中必需的文件名（与下载文件逐项对应，用于完整性校验）。
         */
        List<String> requiredFiles,

        /**
         * 解码器层数。
         */
        int numLayers,

        /**
         * 注意力头数。
         */
        int numHeads,

        /**
         * 单头注意力维度。
         */
        int headDim,

        /**
         * EOS token id。
         */
        long eosId,

        /**
         * decoder 起始 token id。
         */
        long decoderStartId) {

    /**
     * t5-small 模型定义（modelscope: Xenova/t5-small，int8 量化，6 层 8 头 512 维，词表 32128）。
     * <p>嵌入式场景期望模型 jar 打包在 {@code nlp/seq2seq/t5-small/} 下（文件名与下载文件一致）。</p>
     */
    public static final Seq2SeqModelDefinition T5_SMALL = new Seq2SeqModelDefinition(
            "t5-seq2seq",
            "nlp/seq2seq/t5-small/",
            "Xenova/t5-small",
            List.of(
                    "onnx/encoder_model_int8.onnx",
                    "onnx/decoder_model_int8.onnx",
                    "onnx/decoder_with_past_model_int8.onnx",
                    "tokenizer.json"),
            List.of(
                    "encoder_model_int8.onnx",
                    "decoder_model_int8.onnx",
                    "decoder_with_past_model_int8.onnx",
                    "tokenizer.json"),
            6,
            8,
            64,
            1L,
            0L);
}