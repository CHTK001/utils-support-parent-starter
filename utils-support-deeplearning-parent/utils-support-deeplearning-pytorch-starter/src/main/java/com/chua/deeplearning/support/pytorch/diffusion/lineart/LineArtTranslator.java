package com.chua.deeplearning.support.pytorch.diffusion.lineart;

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
   * 线art 线稿条件图 Translator。
 * <p>ControlNet / img2img 线稿预处理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LineArtTranslator implements Translator<Image, Image> {

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

    /** 创建 线arttranslator 实例 */
    public LineArtTranslator() {
        this(512, 512);
    }

    /**
      * 创建 线arttranslator 实例
     * @param imageResolution 镜像resolution
     * @param imageResolution int
     * @param detectResolution detectresolution
     */
    public LineArtTranslator(int imageResolution, int detectResolution) {
        this.imageResolution = imageResolution;
        this.detectResolution = detectResolution;
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
        array = array.transpose(2, 0, 1).div(255f);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray line = list.singletonOrThrow();
        if (line.getShape().dimension() == 4 && line.getShape().get(0) == 1) {
            line = line.squeeze(0);
        }
        line = line.mul(255.0f).clip(0, 255);
        Image img = ImageFactory.getInstance().fromNDArray(line);
        int[] hw = DiffusionResizeHelper.resize64(height, width, imageResolution);
        line = NDImageUtils.resize(img.toNDArray(ctx.getNDManager()), hw[1], hw[0], Image.Interpolation.BILINEAR);
        line = line.neg().add(255);
        return ImageFactory.getInstance().fromNDArray(line);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
