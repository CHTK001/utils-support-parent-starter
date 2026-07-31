package com.chua.deeplearning.support.onnx.classification;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * EfficientNet-Lite0              Translator   
 *
 * @author CH
 * @since 2026-05-10
 */
public class EfficientNetLite0ClassificationTranslator implements Translator<Image, Classifications> {

    private static final int DEFAULT_CLASS_COUNT = 1000;
    private List<String> runtimeLabels = defaultLabels(DEFAULT_CLASS_COUNT);

    public EfficientNetLite0ClassificationTranslator() {
    }

    @Override
    public void prepare(TranslatorContext ctx) throws Exception {
        List<String> labels = loadLabels(ctx.getModel().getModelPath());
        if (!labels.isEmpty()) {
            runtimeLabels = labels;
        }
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, 224, 224);
        // HWC -> CHW
        array = array.transpose(2, 0, 1);
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(255f).expandDims(0);
        return new NDList(array);
    }

    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();
        if (output.getShape().dimension() == 2 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }
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
    public Batchifier getBatchifier() {
        return null;
    }

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

    private static List<String> defaultLabels(int size) {
        List<String> labels = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            labels.add("class-" + i);
        }
        return labels;
    }

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
