package com.chua.deeplearning.support.pytorch.face.recognition;

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
* 洞见face / arcface 人脸特征 Translator。
* <p>输入 112x112，归一化到 [-1,1]，输出 L2 归一化特征向量。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class InsightFaceTranslator implements Translator<Image, float[]> {

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        Pipeline pipeline = new Pipeline();
        pipeline
                .add(new Resize(112, 112))
                .add(new ToTensor())
                .add(new Normalize(
                        new float[]{0.5f, 0.5f, 0.5f},
                        new float[]{0.5f, 0.5f, 0.5f}));
        return pipeline.transform(new NDList(array));
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();
        while (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }
        if (output.getShape().dimension() > 1) {
            output = output.reshape(output.size());
        }
        float[] features = output.toFloatArray();
        return l2Normalize(features);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
    * lnormalize
    *
    * @param features 特征
    * @return l2Normalize的结果
     */
    private static float[] l2Normalize(float[] features) {
        double sum = 0.0;
        for (float f : features) {
            sum += f * f;
        }
        float norm = (float) Math.sqrt(sum);
        if (norm <= 1e-12f) {
            return features;
        }
        float[] result = new float[features.length];
        for (int i = 0; i < features.length; i++) {
            result[i] = features[i] / norm;
        }
        return result;
    }
}
