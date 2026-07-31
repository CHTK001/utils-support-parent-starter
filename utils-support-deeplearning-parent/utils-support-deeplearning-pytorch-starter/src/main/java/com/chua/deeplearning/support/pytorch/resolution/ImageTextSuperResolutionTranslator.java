package com.chua.deeplearning.support.pytorch.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * 图文超分 Translator（短边对齐后归一化到 [-1,1]）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImageTextSuperResolutionTranslator implements Translator<Image, Image> {

    /**
     * 检测/短边分辨率。
     */
    private final int detectResolution;

    public ImageTextSuperResolutionTranslator() {
        this(512);
    }

    public ImageTextSuperResolutionTranslator(int detectResolution) {
        this.detectResolution = detectResolution;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager()).toType(DataType.FLOAT32, false);
        float upScale = (float) detectResolution / (float) Math.max(1, input.getHeight());
        int resizedWidth = Math.max(1, (int) (upScale * input.getWidth()));
        array = NDImageUtils.resize(array, resizedWidth, detectResolution);
        array = array.transpose(2, 0, 1).div(255.0f);
        NDArray mean = ctx.getNDManager().create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        if (array.getShape().dimension() == 4 && array.getShape().get(0) == 1) {
            array = array.squeeze(0);
        }
        array = array.clip(-1f, 1f);
        array = array.add(1f).div(2f).mul(255f).clip(0, 255).toType(DataType.UINT8, false);
        return ImageFactory.getInstance().fromNDArray(array);
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
