package com.chua.deeplearning.support.pytorch.depth;

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

/**
 * midas 深度估计 Translator。
 * <p>输入 RGB 图，输出可视化深度图。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MidasDepthTranslator implements Translator<Image, Image> {

    /**
     * 检测分辨率。
     */
    private final int detectResolution;

    /**
     * 输出分辨率。
     */
    private final int imageResolution;

    /**
     * 原图宽。
     */
    private int width;

    /**
     * 原图高。
     */
    private int height;

    /** 创建 midas深度translator 实例 */
    public MidasDepthTranslator() {
        this(512, 512);
    }

    /**
    * 创建 midas深度translator 实例
    * @param detectResolution detectresolution
    * @param detectResolution int
    * @param imageResolution 镜像resolution
    */
    public MidasDepthTranslator(int detectResolution, int imageResolution) {
        this.detectResolution = detectResolution;
        this.imageResolution = imageResolution;
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        int[] hw = resize64(height, width, detectResolution);
        array = NDImageUtils.resize(array, hw[1], hw[0], Image.Interpolation.AREA);
        array = array.div(127.5f).sub(1.0f);
        array = array.transpose(2, 0, 1);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray depthPt = list.singletonOrThrow();
        NDArray min = depthPt.min();
        depthPt = depthPt.sub(min);
        NDArray max = depthPt.max();
        depthPt = depthPt.div(max);
        depthPt = depthPt.mul(255.0).clip(0, 255).toType(DataType.UINT8, false);
        depthPt = depthPt.expandDims(0);
        NDArray display = toDisplayNdArray(depthPt);
        int[] hw = resize64(height, width, imageResolution);
        display = NDImageUtils.resize(display, hw[1], hw[0], Image.Interpolation.BILINEAR);
        return ImageFactory.getInstance().fromNDArray(display);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
    * 转为displayndarray
    *
    * @param depthPt 深度pt
    * @return 转为displayndarray的结果
    */
    private NDArray toDisplayNdArray(NDArray depthPt) {
        NDArray normalized = depthPt;
        while (normalized.getShape().dimension() > 3 && normalized.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            normalized = normalized.squeeze(0);
        }
        if (normalized.getShape().dimension() == 3 && normalized.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            normalized = normalized.squeeze(0);
        }
        if (normalized.getShape().dimension() == 2) {
            return NDArrays.stack(new NDList(normalized, normalized, normalized), 2);
        }
        if (normalized.getShape().dimension() == 3 && normalized.getShape().get(2) == 1) {
            NDArray channel = normalized.squeeze(2);
            return NDArrays.stack(new NDList(channel, channel, channel), 2);
        }
        if (normalized.getShape().dimension() == 3 && normalized.getShape().get(0) == 3) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            return normalized.transpose(1, 2, 0);
        }
        if (normalized.getShape().dimension() == 3 && normalized.getShape().get(2) == 3) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported MiDaS depth shape: " + normalized.getShape());
    }

    /**
     * 调整大小
     *
     * @param h h
     * @param w w
     * @param resolution resolution
     * @return resize64的结果
     */
    private int[] resize64(double h, double w, double resolution) {
        double k = resolution / Math.min(h, w);
        h *= k;
        w *= k;
        h = Math.max(64, Math.round(h / 64.0) * 64);
        w = Math.max(64, Math.round(w / 64.0) * 64);
        return new int[]{(int) h, (int) w};
    }
}
