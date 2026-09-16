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
* 飞桨 simnet BOW 文本相似度 Translator。
* <p>输入 [query tokens, title tokens]，输出相似度分数。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class SimnetBowTranslator implements Translator<String[][], float[]> {

    /**
    * 词表。
     */
    private final Map<String, Long> word2Id = new HashMap<>();

    /**
    * unk。
     */
    private long unkId;

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        try (InputStream is = open(model)) {
            List<String> lines = Utils.readLines(is, true);
            long idx = 0;
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length >= 2) {
                    try {
                        word2Id.put(parts[0], Long.parseLong(parts[1]));
                    } catch (NumberFormatException e) {
                        word2Id.put(parts[0], idx++);
                    }
                } else {
                    word2Id.put(parts[0], idx++);
                }
            }
        }
        unkId = word2Id.getOrDefault("<unk>", (long) word2Id.size());
    }

    /**
    * 打开
    *
    * @param model 模型
    * @return 打开的结果
     */
    private InputStream open(Model model) throws IOException {
        String[] names = {"vocab.txt", "assets/vocab.txt", "word_dict.txt"};
        for (String n : names) {
            try {
                return model.getArtifact(n).openStream();
            } catch (Exception ignored) {
            }
        }
        throw new IOException("SimNet vocab 未找到");
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, String[][] input) {
        NDManager manager = ctx.getNDManager();
        String[] query = input != null && input.length > 0 ? input[0] : new String[0];
        String[] title = input != null && input.length > 1 ? input[1] : new String[0];
        NDArray q = toIds(manager, query, "query");
        NDArray t = toIds(manager, title, "title");
        return new NDList(q, t);
    }

    /**
    * 转为标识
    *
    * @param manager 管理器
    * @param tokens 令牌
    * @param name 名称
    * @return 转为标识的结果
     */
    private NDArray toIds(NDManager manager, String[] tokens, String name) {
        List<Long> ids = new ArrayList<>();
        if (tokens != null) {
            for (String token : tokens) {
                ids.add(word2Id.getOrDefault(token, unkId));
            }
        }
        if (ids.isEmpty()) {
            ids.add(unkId);
        }
        long[] arr = ids.stream().mapToLong(Long::longValue).toArray();
        NDArray nd = manager.create(arr, new Shape(arr.length, 1));
        nd.setName(name);
        return nd;
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        return list.getFirst().toFloatArray();
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
