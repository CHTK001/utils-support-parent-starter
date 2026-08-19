package com.chua.deeplearning.support.onnx.seq2seq;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * IDEA-CCNL/Randeng-BART-139M Seq2Seq ONNX 翻译器
 * <p>
 * 清华大学 IDEA-CCNL 在悟道 180G 中文语料上重训的 BART 中文版，
 * 参数量约 139M，对标英文 BART-base 级规模。词表、tokenizer、位置嵌入均针对中文优化。
 * 适用于轻量级中文摘要、文本生成、信息抽取等任务。
 * </p>
 * <p>
 * 模型来源：huggingface.co/IDEA-CCNL/Randeng-BART-139M
 * 架构：Encoder-Decoder (ONNX: model.onnx)
 * 输入：中文文本字符串
 * 输出：生成的中文字符串
 * </p>
 * <p>
 * 输入流程：
 * <ol>
 *   <li>HuggingFaceTokenizer (SentencePiece) 将中文文本编码为 input_ids / attention_mask</li>
 *   <li>ONNX 模型正向推理得到 logits</li>
 *   <li>argmax 取每步最优 token ID</li>
 *   <li>Tokenizer decode 得到中文结果文本</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RandengBartTranslator implements Translator<String, String> {

    /** 最大输入长度 */
    /** Max_input_length */
    private static final int MAX_INPUT_LENGTH = 1024;

    /** 分词器 */
    /** Tokenizer */
    private HuggingFaceTokenizer tokenizer;

    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tokenizerPath = findFile(modelRoot, "tokenizer.json");
        if (Files.exists(tokenizerPath)) {
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optPadding(true)
                    .optMaxLength(MAX_INPUT_LENGTH)
                    .build();
            log.debug("[RandengBart] Tokenizer loaded: {}", tokenizerPath);
        } else {
            log.warn("[RandengBart] tokenizer.json not found");
        }
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String input) {
        if (tokenizer == null) {
            throw new IllegalStateException("RandengBart tokenizer not initialized");
        }

        Encoding encoding = tokenizer.encode(input);
        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();

        NDManager manager = ctx.getNDManager();
        NDArray ids = manager.create(inputIds).expandDims(0);
        ids.setName("input_ids");

        NDArray mask = manager.create(attentionMask).expandDims(0);
        mask.setName("attention_mask");

        return new NDList(ids, mask);
    }

    @Override
    public String processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = list.singletonOrThrow();
        NDArray tokenIds = logits.argMax(2);
        long[] ids = tokenIds.toLongArray();

        String text = tokenizer.decode(ids);
        return text.trim();
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Path.of(".");
        }
        if (Files.isRegularFile(modelPath)) {
            return modelPath.getParent();
        }
        return modelPath;
    }

    private static Path findFile(Path root, String name) {
        Path p = root.resolve(name);
        if (Files.exists(p)) {
            return p;
        }
        if (root.getParent() != null) {
            p = root.getParent().resolve(name);
            if (Files.exists(p)) {
                return p;
            }
        }
        return root.resolve(name);
    }
}
