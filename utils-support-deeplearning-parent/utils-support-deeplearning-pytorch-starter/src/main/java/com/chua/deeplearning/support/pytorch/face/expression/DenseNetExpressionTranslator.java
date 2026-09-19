package com.chua.deeplearning.support.pytorch.face.expression;

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

import java.util.ArrayList;
import java.util.List;

/**
 * densenet 表情识别 Translator。
 * <p>7 类：angry / disgust / fear / happy / sad / surprise / neutral。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DenseNetExpressionTranslator implements Translator<Image, Classifications> {

    /** 标签列表 */
    /** 标签 */
    private static final List<String> LABELS = List.of(
            "angry", "disgust", "fear", "happy", "sad", "surprise", "neutral"
    );

    /** 图像尺寸 */
    /** 图片尺寸 */
    private final int imageSize;

    /** 创建 densenetexpressiontranslator 实例 */
    public DenseNetExpressionTranslator() {
        this(224);
    }

    /**
    * 创建 densenetexpressiontranslator 实例
    * @param imageSize 镜像大小
    */
    public DenseNetExpressionTranslator(int imageSize) {
        this.imageSize = imageSize;
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        Shape shape = array.getShape();
        long height = shape.get(0); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        long width = shape.get(1);
        if (height != imageSize || width != imageSize) {
            array = NDImageUtils.resize(array, imageSize, imageSize);
        }
        array = array.transpose(2, 0, 1);
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(255.0f);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();
        if (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            output = output.squeeze(0);
        }
        NDArray probs = output.softmax(-1);
        float[] scores = probs.toFloatArray();
        List<Double> probabilities = new ArrayList<>(scores.length);
        for (float score : scores) {
            probabilities.add((double) score);
        }
        List<String> labels = scores.length == LABELS.size()
                ? LABELS
                : defaultLabels(scores.length);
        return new Classifications(labels, probabilities);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
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
            labels.add("class_" + i);
        }
        return labels;
    }
}
