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
 * LineArt Anime 动漫线稿条件图 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LineArtAnimeTranslator implements Translator<Image, Image> {

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

    /** 创建 LineArtAnimeTranslator 实例 */
    public LineArtAnimeTranslator() {
        this(512);
    }

    /**
     * 创建 LineArtAnimeTranslator 实例
     * @param imageResolution imageResolution
     */
    public LineArtAnimeTranslator(int imageResolution) {
        this.imageResolution = imageResolution;
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        int hn = 256 * (int) Math.ceil((float) height / 256.0);
        int wn = 256 * (int) Math.ceil((float) width / 256.0);
        array = NDImageUtils.resize(array, wn, hn, Image.Interpolation.BICUBIC);
        array = array.transpose(2, 0, 1).div(127.5f).sub(1.0f);
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray line = list.singletonOrThrow();
        if (line.getShape().dimension() == 4 && line.getShape().get(0) == 1) {
            line = line.squeeze(0);
        }
        line = line.mul(127.5f).add(127.5f);
        Image img = ImageFactory.getInstance().fromNDArray(line);
        line = NDImageUtils.resize(img.toNDArray(ctx.getNDManager()), width, height, Image.Interpolation.BICUBIC);
        line = line.clip(0, 255).toType(DataType.UINT8, false);
        int[] hw = DiffusionResizeHelper.resize64(height, width, imageResolution);
        img = ImageFactory.getInstance().fromNDArray(line);
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
