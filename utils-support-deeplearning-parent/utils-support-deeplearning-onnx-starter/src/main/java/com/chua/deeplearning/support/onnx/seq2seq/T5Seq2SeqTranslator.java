package com.chua.deeplearning.support.onnx.seq2seq;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
* T5 Seq2Seq ONNX                 
* <p>
* T5 (文本-转为-文本 调动 转换)
* 翻译、summarization、QA、文本 generation
* 编码器_模型.onnx + 解码器_with_past_模型.onnx
* </p>
* <p>
* : Xenova/t5-small / t5-基础
* : 编码器_模型.onnx -> 编码器_hidden_状态
* : 解码器_with_past_模型.onnx -> autoregressive -> logits -> 文本
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class T5Seq2SeqTranslator implements Translator<String, String> {

    /** 最大输入长度 */
    /** 最大_输入_长度 */
    private static final int MAX_INPUT_LENGTH = 512;
    /** 最大输出长度 */
    /** 最大_输出_长度 */
    private static final int MAX_OUTPUT_LENGTH = 128;
    /** 结束符标识 */
    /** Eos_标识 */
    private static final long EOS_ID = 1L;

    /** 分词器 */
    /** Tokenizer */
    private HuggingFaceTokenizer tokenizer;
    /** 模型根目录 */
    /** 模型根级 */
    private Path modelRoot;

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tokenizerPath = findFile(modelRoot, "tokenizer.json");
        if (Files.exists(tokenizerPath)) {
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optPadding(false)
                    .optMaxLength(MAX_INPUT_LENGTH)
                    .build();
            log.debug("[T5Seq2Seq] Tokenizer loaded: {}", tokenizerPath);
        } else {
            log.warn("[T5Seq2Seq] tokenizer.json not found");
        }
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, String input) {
        if (tokenizer == null) {
            throw new IllegalStateException("T5Seq2Seq tokenizer not initialized");
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
    /** 处理输出 */
    public String processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = list.singletonOrThrow();
        NDArray tokenIds = logits.argMax(2);
        long[] ids = tokenIds.toLongArray();

        int eosPos = ids.length;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == EOS_ID) {
                eosPos = i;
                break;
            }
        }

        long[] trimmed = Arrays.copyOf(ids, eosPos);
        String text = tokenizer.decode(trimmed);
        return text.trim();
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
    * 解析模型根
    *
    * @param modelPath 模型路径
    * @return resolve模型根的结果
     */
    private Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Path.of(".");
        }
        if (Files.isRegularFile(modelPath)) {
            return modelPath.getParent();
        }
        return modelPath;
    }

    /**
    * 查找文件
    *
    * @param root 根
    * @param name 名称
    * @return find文件的结果
     */
    private Path findFile(Path root, String name) {
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
