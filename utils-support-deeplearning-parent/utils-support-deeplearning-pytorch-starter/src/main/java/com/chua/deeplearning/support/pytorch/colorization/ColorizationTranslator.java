package com.chua.deeplearning.support.pytorch.colorization;

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
 * 图像上色 Translator（灰度 → 彩色）。
 * <p>
 * 简化版：将输入转为灰度单通道并 resize 到 256，输出彩色图。
 * 适用于常见 Colorization TorchScript 模型。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ColorizationTranslator implements Translator<Image, Image> {

    /**
     * 模型输入尺寸。
     */
    private static final int SIZE = 256;

    /**
     * 原图宽。
     */
    private int width;

    /**
     * 原图高。
     */
    private int height;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, SIZE, SIZE);
        // 转灰度近似：取均值通道
        NDArray gray = array.mean(new int[]{2}).expandDims(2);
        gray = gray.transpose(2, 0, 1);
        if (!DataType.FLOAT32.equals(gray.getDataType())) {
            gray = gray.toType(DataType.FLOAT32, false);
        }
        gray = gray.div(255.0f);
        return new NDList(gray);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();
        if (output.getShape().dimension() == 4 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }
        // CHW -> HWC
        if (output.getShape().dimension() == 3 && output.getShape().get(0) <= 3) {
            output = output.transpose(1, 2, 0);
        }
        output = output.clip(0, 1).mul(255f).toType(DataType.UINT8, false);
        if (width > 0 && height > 0) {
            output = NDImageUtils.resize(output, width, height);
        }
        return ImageFactory.getInstance().fromNDArray(output);
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
