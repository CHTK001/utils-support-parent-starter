package com.chua.deeplearning.support.tensorflow.classification;

import ai.djl.Model;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * TensorFlow MobileNet / ImageNet 分类 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MobilenetClassificationTranslator implements Translator<Image, Classifications> {

    /**
     * 默认输入图像边长（像素）
     */
    private static final int DEFAULT_IMAGE_SIZE = 224;

    /**
     * 归一化分母
     */
    private static final float NORM_DENOMINATOR = 127.5f;

    /**
     * 归一化偏移量
     */
    private static final float NORM_OFFSET = 1.0f;

    /**
     * 类别标签候选文件名列表
     */
    private static final String[] LABEL_CANDIDATES = {
            "synset.txt", "labels.txt", "label_list.txt", "imagenet_classes.txt"
    };

    /**
     * 输入图像边长（像素）
     */
    private final int imageSize;

    /**
     * ImageNet 类别名称列表
     */
    private List<String> classes;

    /**
     * 构造 MobileNet 分类 Translator，使用默认输入尺寸 224。
     */
    public MobilenetClassificationTranslator() {
        this(DEFAULT_IMAGE_SIZE);
    }

    /**
     * 构造 MobileNet 分类 Translator。
     *
     * @param imageSize 输入图像边长（像素）
     */
    public MobilenetClassificationTranslator(int imageSize) {
        this.imageSize = imageSize;
    }

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        for (String name : LABEL_CANDIDATES) {
            try (InputStream is = model.getArtifact(name).openStream()) {
                classes = Utils.readLines(is, true);
                return;
            } catch (Exception ignored) {
            }
        }
        classes = List.of();
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, imageSize, imageSize);
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }
        // TF MobileNet 常见：[-1,1]
        array = array.div(NORM_DENOMINATOR).sub(NORM_OFFSET);
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        NDArray probabilities = list.singletonOrThrow();
        if (classes == null || classes.isEmpty()) {
            long n = probabilities.size();
            List<String> synthetic = new java.util.ArrayList<>((int) n);
            for (int i = 0; i < n; i++) {
                synthetic.add("class_" + i);
            }
            return new Classifications(synthetic, probabilities);
        }
        return new Classifications(classes, probabilities);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
