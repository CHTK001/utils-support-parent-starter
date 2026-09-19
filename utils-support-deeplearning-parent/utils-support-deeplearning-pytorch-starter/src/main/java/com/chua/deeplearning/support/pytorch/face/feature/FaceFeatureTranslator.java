package com.chua.deeplearning.support.pytorch.face.feature;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.transform.Normalize;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Pipeline;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * 通用人脸特征 Translator（160x160，facenet 风格）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FaceFeatureTranslator implements Translator<Image, float[]> {

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        Pipeline pipeline = new Pipeline();
        pipeline
                .add(new Resize(160))
                .add(new ToTensor())
                .add(new Normalize(
                        new float[]{127.5f / 255.0f, 127.5f / 255.0f, 127.5f / 255.0f},
                        new float[]{128.0f / 255.0f, 128.0f / 255.0f, 128.0f / 255.0f}));
        return pipeline.transform(new NDList(array));
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();
        while (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            output = output.squeeze(0);
        }
        if (output.getShape().dimension() > 1) {
            output = output.reshape(output.size());
        }
        float[] feature = output.toFloatArray();
        return l2Normalize(feature);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
    * lnormalize
    *
    * @param feature 特征
    * @return l2Normalize的结果
    */
    private static float[] l2Normalize(float[] feature) {
        double sum = 0;
        for (float v : feature) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm < 1e-12) {
            return feature;
        }
        float[] result = new float[feature.length];
        for (int i = 0; i < feature.length; i++) {
            result[i] = (float) (feature[i] / norm);
        }
        return result;
    }
}
