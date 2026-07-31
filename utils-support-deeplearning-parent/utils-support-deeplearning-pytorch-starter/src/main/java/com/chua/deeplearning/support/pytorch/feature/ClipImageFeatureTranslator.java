package com.chua.deeplearning.support.pytorch.feature;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * CLIP 图像特征 Translator。
 * <p>输出 L2 归一化后的特征向量。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ClipImageFeatureTranslator implements Translator<Image, float[]> {

    /**
     * 输入尺寸。
     */
    private final int imageSize;

    public ClipImageFeatureTranslator() {
        this(224);
    }

    public ClipImageFeatureTranslator(int imageSize) {
        this.imageSize = imageSize;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR)
                .toType(DataType.FLOAT32, false);
        array = NDImageUtils.resize(array, imageSize, imageSize);
        array = array.transpose(2, 0, 1).div(255f);
        NDArray mean = ctx.getNDManager().create(new float[]{0.48145466f, 0.4578275f, 0.40821073f}, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(new float[]{0.26862954f, 0.26130258f, 0.27577711f}, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);
        return new NDList(array);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray emb = list.get(0);
        if (emb.getShape().dimension() > 1 && emb.getShape().get(0) == 1) {
            emb = emb.squeeze(0);
        }
        float[] values = emb.toFloatArray();
        double norm = 0d;
        for (float v : values) {
            norm += v * v;
        }
        norm = Math.sqrt(Math.max(norm, 1e-12d));
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) (values[i] / norm);
        }
        return values;
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
