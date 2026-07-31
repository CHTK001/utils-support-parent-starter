package com.chua.deeplearning.support.onnx.feature;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * MobileCLIP S0                    Translator   
 *
 * <p>       MobileCLIP S0 vision encoder     512                         
 *              CLIP   256  256 BICUBIC        +                CLIP mean/std             
 *              512                         
 * </p>
 *
 * @author CH
 * @since 2026-05-02
 */
public class MobileClipImageFeatureTranslator implements Translator<Image, float[]> {

    private static final float[] IMAGE_MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] IMAGE_STD = {0.26862954f, 0.26130258f, 0.27577711f};
    private static final int IMAGE_SIZE = 256;

    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        float percent = (float) IMAGE_SIZE / Math.min(input.getWidth(), input.getHeight());
        int resizedWidth = Math.round(input.getWidth() * percent);
        int resizedHeight = Math.round(input.getHeight() * percent);
        array = NDImageUtils.resize(array, resizedWidth, resizedHeight, Image.Interpolation.BICUBIC);
        array = NDImageUtils.centerCrop(array, IMAGE_SIZE, IMAGE_SIZE);
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.transpose(2, 0, 1).div(255f);

        NDArray mean = ctx.getNDManager().create(IMAGE_MEAN, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(IMAGE_STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std).expandDims(0);
        return new NDList(array);
    }

    @Override
    @Nonnull
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();
        if (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }
        return output.toFloatArray();
    }

    @Nullable
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
