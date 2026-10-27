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
 * 亚马逊 Rekognition 风格人脸特征 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AmazonFaceFeatureTranslator implements Translator<Image, float[]> {

    /**
     * 处理输入
    */
    @Override
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

    /**
     * 处理输出
    */
    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        return FaceEmbeddingHelper.toFeature(list);
    }

    /**
     * 获取Batchifier
    */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
