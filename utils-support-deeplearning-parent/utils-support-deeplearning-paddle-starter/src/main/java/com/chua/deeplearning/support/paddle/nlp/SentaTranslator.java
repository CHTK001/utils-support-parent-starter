package com.chua.deeplearning.support.paddle.nlp;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paddle 情感分析 Senta Translator。
 * <p>输入分词后的 token 数组，输出情感分数向量。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SentaTranslator implements Translator<String[], float[]> {

    /**
     * 词到 id。
     */
    private final Map<String, String> word2IdDict = new HashMap<>();

    /**
     * 未知词 id。
     */
    private String unkId = "";

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        try (InputStream is = openVocab(model)) {
            List<String> words = Utils.readLines(is, true);
            for (String word : words) {
                if (word == null || word.isEmpty()) {
                    continue;
                }
                String[] ws = word.split("\t");
                if (ws.length >= 2) {
                    word2IdDict.put(ws[0], ws[1]);
                }
            }
        }
        unkId = String.valueOf(word2IdDict.size());
    }

    /** 打开Vocab */
    private InputStream openVocab(Model model) throws IOException {
        String[] candidates = {"assets/vocab.txt", "vocab.txt", "word_dict.txt"};
        for (String name : candidates) {
            try {
                return model.getArtifact(name).openStream();
            } catch (Exception ignored) {
            }
        }
        throw new IOException("Senta vocab 未找到");
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, String[] input) {
        NDManager manager = ctx.getNDManager();
        List<Long> lodList = new ArrayList<>();
        lodList.add(0L);
        List<Long> ids = tokenize(input, lodList);
        int size = lodList.get(lodList.size() - 1).intValue();
        long[] array = new long[size];
        for (int i = 0; i < size; i++) {
            array[i] = i < ids.size() ? ids.get(i) : 0;
        }
        NDArray ndArray = manager.create(array, new Shape(lodList.get(lodList.size() - 1), 1));
        ndArray.setName("words");
        trySetLod(ndArray, 0, lodList.get(lodList.size() - 1));
        return new NDList(ndArray);
    }

    /** Tokenize */
    private List<Long> tokenize(String[] input, List<Long> lod) {
        List<Long> wordIds = new ArrayList<>();
        for (String word : input) {
            String wordId = word2IdDict.get(word);
            wordIds.add(Long.valueOf(wordId == null || wordId.isBlank() ? unkId : wordId));
        }
        lod.add((long) wordIds.size());
        return wordIds;
    }

    /** Try设置Lod */
    private void trySetLod(NDArray ndArray, long begin, long end) {
        try {
            // Paddle LoD：若运行时为 PpNDArray 则设置
            Class<?> pp = Class.forName("ai.djl.paddlepaddle.engine.PpNDArray");
            if (pp.isInstance(ndArray)) {
                long[][] lod = new long[1][2];
                lod[0][0] = begin;
                lod[0][1] = end;
                pp.getMethod("setLoD", long[][].class).invoke(ndArray, (Object) lod);
            }
        } catch (Throwable ignored) {
            // 非 Paddle 引擎时忽略
        }
    }

    @Override
    /** 处理Output */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        return list.get(0).toFloatArray();
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
