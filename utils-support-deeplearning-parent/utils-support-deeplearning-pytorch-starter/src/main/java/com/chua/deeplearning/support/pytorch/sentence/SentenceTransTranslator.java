package com.chua.deeplearning.support.pytorch.sentence;

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

/**
 * 句向量 Translator（sentence-transformers 风格）。
 * <p>需要模型产物中附带 vocab.txt。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SentenceTransTranslator implements Translator<String, float[]> {

    /**
     * 最大序列长度。
     */
    private final int maxSequenceLength = 128;

    /**
     * 词表。
     */
    private DefaultVocabulary vocabulary;

    /**
     * BERT 分词器。
     */
    private BertFullTokenizer tokenizer;

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return new StackBatchifier();
    }

    @Override
    /**
     * Prepare
    */
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
    /**
     * 处理输出
    */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = null;
        for (NDArray ndArray : list) {
            String name = ndArray.getName();
            if (name != null && name.equals("sentence_embedding")) {
                array = ndArray;
                break;
            }
        }
        if (array == null) {
            array = list.get(list.size() - 1);
        }
        return array.toFloatArray();
    }

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, String input) {
        List<String> tokens = tokenizer.tokenize(input);
        if (tokens.size() > maxSequenceLength - 2) {
            tokens = tokens.subList(0, maxSequenceLength - 2);
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
        return new NDList(indicesArray, attentionMaskArray);
    }
}
