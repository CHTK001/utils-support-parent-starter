package com.chua.deeplearning.support.onnx.classification;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * efficientnet-Lite0              Translator
 *
 * @author CH
 * @since 2026-05-10
 */
public class EfficientNetLite0ClassificationTranslator implements Translator<Image, Classifications> {

    /** 默认类别数量 */
    /** 默认_类_数量 */
    private static final int DEFAULT_CLASS_COUNT = 1000;
    /** 运行时标签列表 */
    /** Runtimelabels */
    private List<String> runtimeLabels = defaultLabels(DEFAULT_CLASS_COUNT);

    /**
     * 创建 efficientnetlite0classificationtranslator 实例
     */
    public EfficientNetLite0ClassificationTranslator() {
    }

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws Exception {
        List<String> labels = loadLabels(ctx.getModel().getModelPath());
        if (!labels.isEmpty()) {
            runtimeLabels = labels;
        }
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        // OpenCV 预处理：resize 224 + CHW 归一化 → float[] → create() 喂入 djl-onnx
        float[] pixels = com.chua.deeplearning.support.utils.ImageUtils.toTensor(input, 224);
        NDArray array = ctx.getNDManager().create(pixels, new Shape(1, 3, 224, 224));
        array.setName("input");
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();
 // 转为floatarray 已扁平化（[1,1000] → 1000 元素），无需 squeeze
        float[] logits = output.toFloatArray();
        if (logits.length == 0) {
            return new Classifications(List.of(), List.of());
        }

        List<String> labels = runtimeLabels.size() == logits.length
                ? runtimeLabels
                : defaultLabels(logits.length);
        double[] scores = softmax(logits);
        List<Double> probabilities = new ArrayList<>(scores.length);
        for (double score : scores) {
            probabilities.add(score);
        }
        return new Classifications(labels, probabilities);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 加载标签
     *
     * @param modelPath 模型路径
     * @return 加载标签的结果
     */
    private static List<String> loadLabels(Path modelPath) {
        try {
            if (modelPath == null) {
                return List.of();
            }
            Path root = Files.isDirectory(modelPath) ? modelPath : modelPath.getParent();
            if (root == null) {
                return List.of();
            }
            Path synset = root.resolve("synset.txt");
            if (!Files.exists(synset) && root.getParent() != null) {
                synset = root.getParent().resolve("synset.txt");
            }
            if (!Files.exists(synset)) {
                return List.of();
            }
            List<String> labels = new ArrayList<>();
            for (String line : Files.readAllLines(synset)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                labels.add(line.trim());
            }
            return labels;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 默认标签
     *
     * @param size 大小
     * @return 默认标签的结果
     */
    private static List<String> defaultLabels(int size) {
        List<String> labels = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            labels.add("class-" + i);
        }
        return labels;
    }

    /**
     * Softmax
     *
     * @param logits logits
     * @return softmax的结果
     */
    private static double[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float logit : logits) {
            max = Math.max(max, logit);
        }
        double sum = 0d;
        double[] exp = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            exp[i] = Math.exp(logits[i] - max);
            sum += exp[i];
        }
        if (sum <= 0d) {
            return new double[logits.length];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }
}
