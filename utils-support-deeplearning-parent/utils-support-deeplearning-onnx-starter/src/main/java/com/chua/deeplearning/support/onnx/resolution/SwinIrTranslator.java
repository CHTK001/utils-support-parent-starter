package com.chua.deeplearning.support.onnx.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * SwinIR ONNX                 
 * <p>
 *      denoising / super-resolution                   
 * </p>
 * <p>
 *      :
 * <ul>
 *   <li>HWC     CHW                       [0, 1]          
 *   <li>          mean/std                    
 * </ul>
 * <p>
 *      : Heliosoph/swinir-onnx
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SwinIrTranslator implements Translator<Image, Image> {

    /** 均值数组 */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    /** 标准差数组 */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    /** 宽度 */
    private int width;
    /** 高度 */
    private int height;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();

        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }

        array = array.transpose(2, 0, 1).div(255.0f);

        NDArray mean = manager.create(MEAN, new ai.djl.ndarray.types.Shape(3, 1, 1));
        NDArray std = manager.create(STD, new ai.djl.ndarray.types.Shape(3, 1, 1));
        array = array.sub(mean).div(std);

        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray outputImg = list.singletonOrThrow();

        outputImg = outputImg.mul(STD[0]).add(MEAN[0]);
        outputImg = outputImg.clip(0.0f, 1.0f);
        outputImg = outputImg.mul(255.0f).round().toType(DataType.UINT8, false);

        outputImg = outputImg.transpose(1, 2, 0);

        Image img = ImageFactory.getInstance().fromNDArray(outputImg);

        if (width > 0 && height > 0 && (img.getWidth() != width || img.getHeight() != height)) {
            NDArray resized = NDImageUtils.resize(img.toNDArray(ctx.getNDManager()), width, height, Image.Interpolation.BICUBIC);
            img = ImageFactory.getInstance().fromNDArray(resized);
        }

        return img;
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
