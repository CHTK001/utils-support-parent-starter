package com.chua.deeplearning.support.pytorch.diffusion.normal;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.pytorch.diffusion.DiffusionResizeHelper;

/**
* 法线贴图条件图 Translator。
*
* @author CH
* @since 4.0.0.42
 */
public class NetNormalTranslator implements Translator<Image, Image> {

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

    /** 创建 netnormaltranslator 实例 */
    public NetNormalTranslator() {
        this(512, 512);
    }

    /**
    * 创建 netnormaltranslator 实例
    * @param imageResolution 镜像resolution
    * @param imageResolution int
    * @param detectResolution detectresolution
     */
    public NetNormalTranslator(int imageResolution, int detectResolution) {
        this.imageResolution = imageResolution;
        this.detectResolution = detectResolution;
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        int[] hw = DiffusionResizeHelper.resize64(height, width, detectResolution);
        array = NDImageUtils.resize(array, hw[1], hw[0], Image.Interpolation.AREA);
        array = array.transpose(2, 0, 1).div(255f);
        NDArray mean = ctx.getNDManager().create(new float[]{0.485f, 0.456f, 0.406f}, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(new float[]{0.229f, 0.224f, 0.225f}, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray normal = list.singletonOrThrow();
        if (normal.getShape().dimension() == 4 && normal.getShape().get(0) == 1) {
            normal = normal.squeeze(0);
        }
        if (normal.getShape().dimension() == 3 && normal.getShape().get(0) >= 3) {
            normal = normal.get(new NDIndex(":3"));
        }
        normal = normal.add(1).sub(0.5).clip(0, 1);
        normal = normal.mul(255.0f).clip(0, 255).toType(DataType.UINT8, false);
        Image img = ImageFactory.getInstance().fromNDArray(normal);
        int[] hw = DiffusionResizeHelper.resize64(height, width, imageResolution);
        normal = NDImageUtils.resize(img.toNDArray(ctx.getNDManager()), hw[1], hw[0], Image.Interpolation.AREA);
        return ImageFactory.getInstance().fromNDArray(normal);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
