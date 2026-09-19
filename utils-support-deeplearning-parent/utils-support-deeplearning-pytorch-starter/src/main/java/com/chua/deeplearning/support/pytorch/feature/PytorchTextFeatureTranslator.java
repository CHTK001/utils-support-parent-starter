package com.chua.deeplearning.support.pytorch.feature;

import ai.djl.Model;
import ai.djl.modality.nlp.DefaultVocabulary;
import ai.djl.modality.nlp.bert.BertFullTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.StackBatchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文本特征 Translator（BERT 风格，可选中文 令牌 清理）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PytorchTextFeatureTranslator implements Translator<String, float[]> {

    /**
     * 最大序列长度。
     */
    private final int maxSequenceLength = 512;

    /**
     * 是否中文（去掉 ## 子词前缀）。
     */
    private final boolean chinese;

    /**
     * 词表。
     */
    private DefaultVocabulary vocabulary;

    /**
     * 分词器。
     */
    private BertFullTokenizer tokenizer;

    /** 创建 pytorch文本特征translator 实例 */
    public PytorchTextFeatureTranslator() {
        this(false);
    }

    /**
    * 创建 pytorch文本特征translator 实例
    * @param chinese chinese
    */
    public PytorchTextFeatureTranslator(boolean chinese) {
        this.chinese = chinese;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return new StackBatchifier();
    }

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        URL url = model.getArtifact("vocab.txt");
        vocabulary = DefaultVocabulary.builder()
                .optMinFrequency(1)
                .addFromTextFile(url)
                .optUnknownToken("[UNK]")
                .build();
        tokenizer = new BertFullTokenizer(vocabulary, false);
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        return list.getFirst().toFloatArray();
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, String input) {
        List<String> tokens = tokenizer.tokenize(input);
        if (tokens.size() > maxSequenceLength - 2) {
            tokens = tokens.subList(0, maxSequenceLength - 2);
        }
        if (chinese) {
            tokens = tokens.stream().map(e -> e.replace("##", "")).collect(Collectors.toList());
        }
        long[] indices = tokens.stream().mapToLong(vocabulary::getIndex).toArray();
        long[] inputIds = new long[tokens.size() + 2];
        inputIds[0] = vocabulary.getIndex("[CLS]");
        inputIds[inputIds.length - 1] = vocabulary.getIndex("[SEP]");
        System.arraycopy(indices, 0, inputIds, 1, indices.length);

        long[] tokenTypeIds = new long[inputIds.length];
        Arrays.fill(tokenTypeIds, 0);
        long[] attentionMask = new long[inputIds.length];
        Arrays.fill(attentionMask, 1);

        NDManager manager = ctx.getNDManager();
        NDArray indicesArray = manager.create(inputIds);
        indicesArray.setName("input.input_ids");
        NDArray tokenIdsArray = manager.create(tokenTypeIds);
        tokenIdsArray.setName("input.token_type_ids");
        NDArray attentionMaskArray = manager.create(attentionMask);
        attentionMaskArray.setName("input.attention_mask");
        return new NDList(indicesArray, tokenIdsArray, attentionMaskArray);
    }
}
