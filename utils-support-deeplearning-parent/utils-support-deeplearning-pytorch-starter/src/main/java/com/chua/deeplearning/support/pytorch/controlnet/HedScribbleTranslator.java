package com.chua.deeplearning.support.pytorch.controlnet;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * HED 线稿/涂鸦预处理 Translator。
 * <p>输出边缘强度可视化图，用于 ControlNet 条件输入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HedScribbleTranslator implements Translator<Image, Image> {

    /**
     * 输入分辨率。
     */
    private final int resolution;

    /**
     * 原图宽。
     */
    private int width;

    /**
     * 原图高。
     */
    private int height;

    public HedScribbleTranslator() {
        this(512);
    }

    public HedScribbleTranslator(int resolution) {
        this.resolution = resolution;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, resolution, resolution);
        array = array.div(255.0f).transpose(2, 0, 1);
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray out = list.singletonOrThrow();
        if (out.getShape().dimension() == 4 && out.getShape().get(0) == 1) {
            out = out.squeeze(0);
        }
        if (out.getShape().dimension() == 3 && out.getShape().get(0) <= 3) {
            out = out.transpose(1, 2, 0);
        }
        out = out.clip(0, 1).mul(255f).toType(DataType.UINT8, false);
        if (width > 0 && height > 0) {
            out = NDImageUtils.resize(out, width, height);
        }
        return ImageFactory.getInstance().fromNDArray(out);
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
