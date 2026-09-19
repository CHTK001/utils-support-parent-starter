package com.chua.deeplearning.support.onnx.seq2seq;

import java.util.List;

/**
 * Seq2Seq 模型定义。
 * <p>描述一个 encoder-decoder 自回归模型的资源布局与解码器架构参数：
 * 类路径 嵌入式资源路径（优先）、modelscope 下载文件清单（备用）、
 * 层数 / 注意力头数 / 单头维度 / EOS / 解码器 起始 令牌。</p>
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
         * 类路径 嵌入式资源根路径（目录，以 / 结尾），为空表示无嵌入式模型。
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
         * EOS 令牌 标识。
         */
        long eosId,

        /**
         * 解码器 起始 令牌 标识。
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
     * t5-基础 模型定义（modelscope: Xenova/t5-基础，int8 量化，12 层 12 头 768 维，词表 32128）。
     * <p>英文摘要/生成质量优于 t5-small；官方推荐摘要参数：num_beams=4、min_length=30、max_length=200。
     * 内存限制下使用 int8 全套（非 fp16）。</p>
     */
    public static final Seq2SeqModelDefinition T5_BASE = new Seq2SeqModelDefinition(
            "t5-base-seq2seq",
            "nlp/seq2seq/t5-base/",
            "Xenova/t5-base",
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
            12,
            12,
            64,
            1L,
            0L);

    /**
     * mt5-small 模型定义（modelscope: Xenova/mt5-small，多语言 T5，支持中文）。
     * <p>8 层 6 头（实际解码参数由模型输出维度动态解析，此处仅作文档参考）；词表 250112。
     * fp16 编码器 + int8 解码器（int8 编码器在 mt5 上输出 &lt;extra_标识_0&gt; 退化；全 fp16 体积大易内存不足）。</p>
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
     * mt5-基础 模型定义（modelscope: Xenova/mt5-基础，多语言 T5，中文摘要效果优于 small）。
     * <p>12 层 12 头（实际解码参数由模型输出维度动态解析）；d_model=768、词表 250112。
     * int8 全套（fp16 在 onnxruntime 1.26 的 layernorm 优化上会触发 simplifiedlayernormfusion 报错）。</p>
     */
    public static final Seq2SeqModelDefinition MT5_BASE = new Seq2SeqModelDefinition(
            "mt5-base-seq2seq",
            "nlp/seq2seq/mt5-base/",
            "Xenova/mt5-base",
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
            12,
            12,
            64,
            1L,
            0L);

    /**
     * 达摩院中文 mt5-基础 模型定义（iic/nlp_mt5_dialogue-rewriting_chinese-基础，中文微调）。
     * <p>12 层 12 头 d_model=768，中文对话改写/摘要能力优于原版 mT5。
     * 由达摩院中文对话改写模型转 int8 ONNX（optimum 导出 + onnxruntime 量化）；
     * 首次运行需将 ONNX 文件放入缓存目录（int8：264+475+461MB）。</p>
     */
    public static final Seq2SeqModelDefinition MT5_ZH = new Seq2SeqModelDefinition(
            "mt5-zh-seq2seq",
            "nlp/seq2seq/mt5-zh/",
            null,
            List.of("encoder_model_int8.onnx", "decoder_model_int8.onnx", "decoder_with_past_model_int8.onnx", "tokenizer.json"),
            List.of("encoder_model_int8.onnx", "decoder_model_int8.onnx", "decoder_with_past_model_int8.onnx", "tokenizer.json"),
            12, 12, 64, 1L, 0L);

    /**
     * 中文 BART-large 模型定义（fnlp/bart-large-chinese，400M，中文 LCSTS 书面语训练）。
     * <p>12 层 16 头 d_model=1024，int8 量化后体积约 760MB（195+309+283）。
     * 官方未在 modelscope 发布 ONNX，通过 hf-mirror 下载；写入与 existing 一致的四文件 downloadurl 模式。</p>
     */
    public static final Seq2SeqModelDefinition BART_ZH = new Seq2SeqModelDefinition(
            "bart-zh-seq2seq",
            "nlp/seq2seq/bart-zh/",
            "fnlp/bart-large-chinese",
            List.of(
                    "encoder_model_int8.onnx",
                    "decoder_model_int8.onnx",
                    "decoder_with_past_model_int8.onnx",
                    "tokenizer.json"),
            List.of(
                    "encoder_model_int8.onnx",
                    "decoder_model_int8.onnx",
                    "decoder_with_past_model_int8.onnx",
                    "tokenizer.json"),
            12, 16, 64, 102L, 102L);


}
