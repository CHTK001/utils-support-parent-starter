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
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
* BART Seq2Seq ONNX                 
* <p>
* BART (Bidirectional 和 Auto-Regressive Transformers)
* 文本 summarization、machine 翻译、抽象 QA、释义
* </p>
* <p>
*      : Xenova/bart-large-cnn
* : 输入_标识 + attention_mask -> logits [批量, seq_len, vocab_大小]
* </p>
* <p>
*      :       
* <ol>
*   <li>Tokenizer      input_ids / attention_mask</li>
*   <li>ONNX      logits</li>
*   <li>argmax(vocab)     token IDs</li>
*   <li>Tokenizer decode      </li>
* </ol>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class BartSeq2SeqTranslator implements Translator<String, String> {

    /** 最大输入长度 */
    /** 最大_输入_长度 */
    private static final int MAX_INPUT_LENGTH = 1024;

    /** 分词器 */
    /** Tokenizer */
    private HuggingFaceTokenizer tokenizer;

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tokenizerPath = modelRoot.resolve("tokenizer.json");
        if (!Files.exists(tokenizerPath) && modelRoot.getParent() != null) {
            tokenizerPath = modelRoot.getParent().resolve("tokenizer.json");
        }
        if (Files.exists(tokenizerPath)) {
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optPadding(true)
                    .optMaxLength(MAX_INPUT_LENGTH)
                    .build();
            log.debug("[BartSeq2Seq] Tokenizer loaded: {}", tokenizerPath);
        } else {
            log.warn("[BartSeq2Seq] tokenizer.json not found at {}", tokenizerPath);
        }
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, String input) {
        if (tokenizer == null) {
            throw new IllegalStateException("BartSeq2Seq tokenizer not initialized");
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

        // logits: [batch, seq_len, vocab_size] -> token IDs [batch, seq_len]
        NDArray tokenIds = logits.argMax(2);
        long[] ids = tokenIds.toLongArray();

        // decode
        String text = tokenizer.decode(ids);
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
    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Path.of(".");
        }
        if (Files.isRegularFile(modelPath)) {
            return modelPath.getParent();
        }
        return modelPath;
    }
}
