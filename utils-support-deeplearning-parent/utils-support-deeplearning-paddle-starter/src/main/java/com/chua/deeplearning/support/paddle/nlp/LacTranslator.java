package com.chua.deeplearning.support.paddle.nlp;

import com.chua.common.support.reflection.ReflectUtils;
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
* 飞桨 LAC 中文分词/词性标注 Translator。
* <p>输出 [token, label] 二维数组。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class LacTranslator implements Translator<String, String[][]> {

    /**
    * 词 → 标识。
     */
    private final Map<String, String> word2IdDict = new HashMap<>();

    /**
    * 标识 → 标签。
     */
    private final Map<String, String> id2LabelDict = new HashMap<>();

    /**
    * 全角半角替换。
     */
    private final Map<String, String> wordReplaceDict = new HashMap<>();

    /**
    * OOV 标识。
     */
    private String oovId;

    /**
    * 原始输入。
     */
    private String input;

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        loadWordDic(model);
        loadTagDic(model);
        loadQ2b(model);
        oovId = word2IdDict.getOrDefault("OOV", "0");
    }

    /**
    * 加载worddic
    *
    * @param model 模型
     */
    private void loadWordDic(Model model) throws IOException {
        try (InputStream is = open(model, "lac/word.dic", "word.dic")) {
            for (String word : Utils.readLines(is, true)) {
                if (word == null || word.isEmpty()) {
                    continue;
                }
                String[] ws = word.split("\t");
                if (ws.length == 1) {
                    word2IdDict.put("", ws[0]);
                } else {
                    word2IdDict.put(ws[1], ws[0]);
                }
            }
        }
    }

    /**
    * 加载标签dic
    *
    * @param model 模型
     */
    private void loadTagDic(Model model) throws IOException {
        try (InputStream is = open(model, "lac/tag.dic", "tag.dic")) {
            for (String word : Utils.readLines(is, true)) {
                if (word == null || word.isEmpty()) {
                    continue;
                }
                String[] ws = word.split("\t");
                if (ws.length >= 2) {
                    id2LabelDict.put(ws[0], ws[1]);
                }
            }
        }
    }

    /**
    * 加载b
    *
    * @param model 模型
     */
    private void loadQ2b(Model model) {
        try (InputStream is = open(model, "lac/q2b.dic", "q2b.dic")) {
            for (String word : Utils.readLines(is, true)) {
                if (word == null) {
                    continue;
                }
                String[] ws = word.split("\t");
                if (ws.length >= 2) {
                    wordReplaceDict.put(ws[0], ws[1]);
                } else if (ws.length == 1) {
                    wordReplaceDict.put(ws[0], "");
                }
            }
        } catch (Exception ignored) {
            // optional
        }
    }

    /**
    * 打开
    *
    * @param model 模型
    * @param names 名称
    * @return 打开的结果
     */
    private InputStream open(Model model, String... names) throws IOException {
        for (String name : names) {
            try {
                return model.getArtifact(name).openStream();
            } catch (Exception ignored) {
            }
        }
        throw new IOException("LAC 词典未找到: " + String.join(",", names));
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, String input) {
        this.input = input == null ? "" : input;
        NDManager manager = ctx.getNDManager();
        List<Long> lodList = new ArrayList<>();
        lodList.add(0L);
        List<Long> wordIds = new ArrayList<>();
        for (int i = 0; i < this.input.length(); i++) {
            String ch = String.valueOf(this.input.charAt(i));
            ch = wordReplaceDict.getOrDefault(ch, ch);
            String id = word2IdDict.getOrDefault(ch, oovId);
            wordIds.add(Long.valueOf(id));
        }
        lodList.add((long) wordIds.size());
        long[] array = wordIds.stream().mapToLong(Long::longValue).toArray();
        NDArray ndArray = manager.create(array, new Shape(array.length, 1));
        ndArray.setName("words");
        trySetLod(ndArray, 0, array.length);
        return new NDList(ndArray);
    }

    /**
    * 尝试设置Lod
    *
    * @param ndArray ndarray
    * @param begin 开始
    * @param end 结束
     */
    private void trySetLod(NDArray ndArray, long begin, long end) {
        try {
            Class<?> pp = ReflectUtils.forName("ai.djl.paddlepaddle.engine.PpNDArray");
            if (pp.isInstance(ndArray)) {
                long[][] lod = new long[1][2];
                lod[0][0] = begin;
                lod[0][1] = end;
                ReflectUtils.invoke(ndArray, "setLoD", void.class, long[][].class, lod);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    /** 处理输出 */
    public String[][] processOutput(TranslatorContext ctx, NDList list) {
        NDArray tags = list.getFirst();
        long[] tagIds = tags.toLongArray();
        List<String> tokens = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String currentLabel = "n";
        for (int i = 0; i < input.length() && i < tagIds.length; i++) {
            String label = id2LabelDict.getOrDefault(String.valueOf(tagIds[i]), "n");
            // BIO: B-/I- 或单字标签
            if (label.startsWith("B-") || label.length() == 1 || i == 0) {
                if (!buf.isEmpty()) {
                    tokens.add(buf.toString());
                    labels.add(currentLabel);
                    buf.setLength(0);
                }
                currentLabel = label.startsWith("B-") ? label.substring(2) : label;
                buf.append(input.charAt(i));
            } else {
                buf.append(input.charAt(i));
                if (label.startsWith("I-")) {
                    currentLabel = label.substring(2);
                }
            }
        }
        if (!buf.isEmpty()) {
            tokens.add(buf.toString());
            labels.add(currentLabel);
        }
        String[][] result = new String[tokens.size()][2];
        for (int i = 0; i < tokens.size(); i++) {
            result[i][0] = tokens.get(i);
            result[i][1] = labels.get(i);
        }
        return result;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
