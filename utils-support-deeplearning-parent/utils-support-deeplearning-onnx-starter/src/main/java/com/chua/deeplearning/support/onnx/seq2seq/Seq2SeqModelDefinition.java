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

    /**
     * mt5-small 模型定义（modelscope: Xenova/mt5-small，多语言 T5，支持中文）。
     * <p>8 层 6 头（实际解码参数由模型输出维度动态解析，此处仅作文档参考）；词表 250112。
     * 编码器使用 fp16（int8 动态量化在 mt5 上会使编码上下文退化，实测生成 <extra_id_0>）。</p>
     */
    public static final Seq2SeqModelDefinition MT5_SMALL = new Seq2SeqModelDefinition(
            "mt5-seq2seq",
            "nlp/seq2seq/mt5-small/",
            "Xenova/mt5-small",
            List.of(
                    "onnx/encoder_model_fp16.onnx",
                    "onnx/decoder_model_int8.onnx",
                    "onnx/decoder_with_past_model_int8.onnx",
                    "tokenizer.json"),
            List.of(
                    "encoder_model_fp16.onnx",
                    "decoder_model_int8.onnx",
                    "decoder_with_past_model_int8.onnx",
                    "tokenizer.json"),
            8,
            6,
            64,
            1L,
            0L);

    /**
     * mt5-base 模型定义（modelscope: Xenova/mt5-base，多语言 T5，中文摘要效果优于 small）。
     * <p>12 层 12 头（实际解码参数由模型输出维度动态解析）；d_model=768、词表 250112。
     * 编码器 fp16 + 解码器 int8（small 验证：int8 编码器在 mT5 上会使上下文退化）。</p>
     */
    public static final Seq2SeqModelDefinition MT5_BASE = new Seq2SeqModelDefinition(
            "mt5-base-seq2seq",
            "nlp/seq2seq/mt5-base/",
            "Xenova/mt5-base",
            List.of(
                    "onnx/encoder_model_fp16.onnx",
                    "onnx/decoder_model_int8.onnx",
                    "onnx/decoder_with_past_model_int8.onnx",
                    "tokenizer.json"),
            List.of(
                    "encoder_model_fp16.onnx",
                    "decoder_model_int8.onnx",
                    "decoder_with_past_model_int8.onnx",
                    "tokenizer.json"),
            12,
            12,
            64,
            1L,
            0L);
}