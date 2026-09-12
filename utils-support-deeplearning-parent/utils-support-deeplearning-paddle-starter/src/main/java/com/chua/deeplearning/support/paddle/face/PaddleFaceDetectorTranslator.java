package com.chua.deeplearning.support.paddle.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
   * 飞桨 人脸检测 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PaddleFaceDetectorTranslator implements Translator<Image, DetectedObjects> {

    /**
     * 缩放比例。
     */
    private final float shrink;

    /**
     * 置信度阈值。
     */
    private final float threshold;

    /**
     * 类别名。
     */
    private final List<String> className;

    /** 创建 飞桨facedetectortranslator 实例 */
    public PaddleFaceDetectorTranslator() {
        this(0.5f, 0.7f);
    }

    /**
      * 创建 飞桨facedetectortranslator 实例
     * @param shrink shrink
     * @param shrink float
     * @param threshold 阈值
     */
    public PaddleFaceDetectorTranslator(float shrink, float threshold) {
        this.shrink = shrink;
        this.threshold = threshold;
        this.className = Arrays.asList("Not Face", "Face");
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        return processImageInput(ctx.getNDManager(), input, shrink);
    }

    @Override
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray result = list.singletonOrThrow();
        float[] probabilities = result.get(":,1").toFloatArray();
        List<String> objectNames = new ArrayList<>();
        List<Double> probabilitiesResult = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (int index = 0; index < probabilities.length; index++) {
            if (probabilities[index] < threshold) {
                continue;
            }
            float[] array = result.get(index).toFloatArray();
            int cls = (int) array[0];
            objectNames.add(cls >= 0 && cls < className.size() ? className.get(cls) : "Face");
            probabilitiesResult.add((double) probabilities[index]);
            boxes.add(new Rectangle(array[2], array[3], array[4] - array[2], array[5] - array[3]));
        }
        return new DetectedObjects(objectNames, probabilitiesResult, boxes);
    }

    /**
     * 处理镜像输入
     *
     * @param manager 管理器
     * @param input 输入
     * @param currentShrink 当前shrink
     * @return 处理镜像输入的结果
     */
    private NDList processImageInput(NDManager manager, Image input, float currentShrink) {
        NDArray array = input.toNDArray(manager);
        Shape shape = array.getShape();
        array = NDImageUtils.resize(array, (int) (shape.get(1) * currentShrink), (int) (shape.get(0) * currentShrink));
        array = array.transpose(2, 0, 1).flip(0);
        NDArray mean = manager.create(new float[]{104f, 117f, 123f}, new Shape(3, 1, 1));
        array = array.sub(mean).mul(0.007843f).expandDims(0);
        return new NDList(array);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
