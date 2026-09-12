package com.chua.deeplearning.support.pytorch.classification;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

/**
* pytorch 镜像net 分类 Translator。
* <p>
* 输入 224x224，镜像net 均值/方差归一化，输出 softmax 分类结果。
* 适用于 Rnet / mobilenet / efficientnet 等 torchscript 模型。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class PytorchImageNetClassificationTranslator implements Translator<Image, Classifications> {

    /**
    * 输入边长。
     */
    private static final int IMAGE_SIZE = 224;

    /**
    * 镜像net 均值。
     */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};

    /**
    * 镜像net 标准差。
     */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    /**
    * 运行时标签。
     */
    private List<String> runtimeLabels = Collections.emptyList();

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
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, IMAGE_SIZE, IMAGE_SIZE);
        array = NDImageUtils.toTensor(array);
        NDArray mean = ctx.getNDManager().create(MEAN, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = list.singletonOrThrow();
        if (logits.getShape().dimension() > 1 && logits.getShape().get(0) == 1) {
            logits = logits.squeeze(0);
        }
        if (!DataType.FLOAT32.equals(logits.getDataType())) {
            logits = logits.toType(DataType.FLOAT32, false);
        }
        NDArray probabilities = logits.softmax(-1);
        float[] scores = probabilities.toFloatArray();
        List<String> labels = runtimeLabels.size() == scores.length
                ? runtimeLabels
                : defaultLabels(scores.length);
        List<Double> probs = new ArrayList<>(scores.length);
        for (float score : scores) {
            probs.add((double) score);
        }
        return new Classifications(labels, probs);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
    * 从模型同目录加载 synset.txt / 标签.txt。
    *
    * @param modelPath 模型路径
    * @return 标签列表
     */
    private static List<String> loadLabels(Path modelPath) {
        if (modelPath == null) {
            return Collections.emptyList();
        }
        Path dir = Files.isDirectory(modelPath) ? modelPath : modelPath.getParent();
        if (dir == null) {
            return Collections.emptyList();
        }
        for (String name : List.of("synset.txt", "labels.txt", "classes.txt")) {
            Path file = dir.resolve(name);
            if (Files.exists(file)) {
                try {
                    return Files.readAllLines(file);
                } catch (Exception ignored) {
                }
            }
        }
        return Collections.emptyList();
    }

    /**
    * 生成默认类别名。
    *
    * @param size 数量
    * @return 标签
     */
    private static List<String> defaultLabels(int size) {
        return IntStream.range(0, Math.max(size, 0))
                .mapToObj(i -> "class_" + i)
                .toList();
    }
}
