package com.chua.deeplearning.support.paddle.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
* 飞桨 人脸关键点 Translator。
*
* @author CH
* @since 4.0.0.42
 */
public class FaceLandmarkTranslator implements Translator<Image, float[]> {

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        if (array.getShape().dimension() > 1 && array.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            array = array.squeeze(0);
        }
        return array.toFloatArray();
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.GRAYSCALE);
        array = NDImageUtils.resize(array, 60, 60, Image.Interpolation.BICUBIC);
        NDArray mean = array.mean();
        float std = std(array);
        array = array.transpose(2, 0, 1).sub(mean).div(Math.max(std, 1e-6f)).expandDims(0);
        return new NDList(array);
    }

    /**
    * Std
    *
    * @param points points
    * @return std的结果
     */
    private float std(NDArray points) {
        float[] arr = points.toType(ai.djl.ndarray.types.DataType.FLOAT32, false).toFloatArray();
        double sum = 0;
        for (float v : arr) {
            sum += v * v;
        }
        return (float) Math.sqrt(sum / Math.max(1, arr.length));
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
