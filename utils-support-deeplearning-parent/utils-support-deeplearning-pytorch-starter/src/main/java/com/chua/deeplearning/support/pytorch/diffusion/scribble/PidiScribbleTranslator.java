package com.chua.deeplearning.support.pytorch.diffusion.scribble;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.pytorch.diffusion.DiffusionResizeHelper;

/**
* pidinet 涂鸦/边缘条件图 Translator（简化版，无 打开cv NMS）。
*
* @author CH
* @since 4.0.0.42
 */
public class PidiScribbleTranslator implements Translator<Image, Image> {

    /**
    * 输出分辨率。
    */
    private final int imageResolution;

    /**
    * 检测分辨率。
    */
    private final int detectResolution;

    /**
    * 是否安全步进量化。
    */
    private final boolean safe;

    /**
    * 原图宽。
    */
    private int width;

    /**
    * 原图高。
    */
    private int height;

    /** 创建 pidiscribbletranslator 实例 */
    public PidiScribbleTranslator() {
        this(512, 512, true);
    }

    /**
    * 创建 pidiscribbletranslator 实例
    * @param imageResolution 镜像resolution
    * @param imageResolution int
    * @param safe 布尔值
    * @param detectResolution detectresolution
    * @param safe safe
    */
    public PidiScribbleTranslator(int imageResolution, int detectResolution, boolean safe) {
        this.imageResolution = imageResolution;
        this.detectResolution = detectResolution;
        this.safe = safe;
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = array.toType(DataType.UINT8, false);
        int[] hw = DiffusionResizeHelper.resize64(height, width, detectResolution);
        array = NDImageUtils.resize(array, hw[1], hw[0], Image.Interpolation.AREA);
        array = array.flip(2);
        array = array.transpose(2, 0, 1).div(255.0f);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray edge = list.get(list.size() - 1);
        if (edge.getShape().dimension() == 4 && edge.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            edge = edge.squeeze(0);
        }
        if (safe) {
            edge = safeStep(edge, 2);
        }
        edge = edge.mul(255.0f).clip(0, 255).toType(DataType.UINT8, false);
        Image edgeImg = ImageFactory.getInstance().fromNDArray(edge);
        int[] hw = DiffusionResizeHelper.resize64(height, width, imageResolution);
        edge = NDImageUtils.resize(edgeImg.toNDArray(ctx.getNDManager()), hw[1], hw[0], Image.Interpolation.BILINEAR);
        // 二值化边缘：>4 为 255，否则 0
        NDArray cutHigh = edge.gt(4);
        NDArray result = edge.zerosLike();
        result = result.add(cutHigh.toType(DataType.FLOAT32, false).mul(255f));
        return ImageFactory.getInstance().fromNDArray(result.toType(DataType.UINT8, false));
    }

    /**
    * safestep
    *
    * @param edge edge
    * @param step step
    * @return safeStep的结果
    */
    private NDArray safeStep(NDArray edge, int step) {
        edge = edge.toType(DataType.FLOAT32, false);
        edge = edge.mul((float) (step + 1));
        edge = edge.toType(DataType.INT32, false).toType(DataType.FLOAT32, false);
        return edge.div(step);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
