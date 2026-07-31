package com.chua.deeplearning.support.pytorch.diffusion.depth;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.pytorch.diffusion.DiffusionResizeHelper;

/**
 * Diffusion 深度条件图 Translator（MiDaS 风格）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DiffusionDepthTranslator implements Translator<Image, Image> {

    /**
     * 输出分辨率。
     */
    private final int imageResolution;

    /**
     * 检测分辨率。
     */
    private final int detectResolution;

    /**
     * 原图宽。
     */
    private int width;

    /**
     * 原图高。
     */
    private int height;

    public DiffusionDepthTranslator() {
        this(512, 512);
    }

    public DiffusionDepthTranslator(int imageResolution, int detectResolution) {
        this.imageResolution = imageResolution;
        this.detectResolution = detectResolution;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        int[] hw = DiffusionResizeHelper.resize64(height, width, detectResolution);
        array = NDImageUtils.resize(array, hw[1], hw[0], Image.Interpolation.AREA);
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(127.5f).sub(1.0f);
        array = array.transpose(2, 0, 1);
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray depthPt = list.singletonOrThrow();
        if (depthPt.getShape().dimension() == 4 && depthPt.getShape().get(0) == 1) {
            depthPt = depthPt.squeeze(0);
        }
        NDArray min = depthPt.min();
        depthPt = depthPt.sub(min);
        NDArray max = depthPt.max();
        depthPt = depthPt.div(max);
        depthPt = depthPt.mul(255.0).clip(0, 255).toType(DataType.UINT8, false);
        NDArray display = toDisplay(depthPt);
        int[] hw = DiffusionResizeHelper.resize64(height, width, imageResolution);
        display = NDImageUtils.resize(display, hw[1], hw[0], Image.Interpolation.BILINEAR);
        return ImageFactory.getInstance().fromNDArray(display);
    }

    private NDArray toDisplay(NDArray depthPt) {
        NDArray normalized = depthPt;
        while (normalized.getShape().dimension() > 3 && normalized.getShape().get(0) == 1) {
            normalized = normalized.squeeze(0);
        }
        if (normalized.getShape().dimension() == 3 && normalized.getShape().get(0) == 1) {
            normalized = normalized.squeeze(0);
        }
        if (normalized.getShape().dimension() == 2) {
            return NDArrays.stack(new NDList(normalized, normalized, normalized), 2);
        }
        if (normalized.getShape().dimension() == 3 && normalized.getShape().get(0) == 3) {
            return normalized.transpose(1, 2, 0);
        }
        if (normalized.getShape().dimension() == 3 && normalized.getShape().get(2) == 3) {
            return normalized;
        }
        if (normalized.getShape().dimension() == 3 && normalized.getShape().get(2) == 1) {
            NDArray ch = normalized.squeeze(2);
            return NDArrays.stack(new NDList(ch, ch, ch), 2);
        }
        return normalized;
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
