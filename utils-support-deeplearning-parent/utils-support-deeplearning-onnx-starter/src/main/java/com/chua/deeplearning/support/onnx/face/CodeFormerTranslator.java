package com.chua.deeplearning.support.onnx.face;

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
 * CodeFormer ONNX                 
 * <p>
 * CodeFormer                                     
 *       face restoration / enhancement                                 
 *       blurry / low-quality / damaged face -> restored face               
 * </p>
 * <p>
 *      : 512x512 RGB Image
 *      : bluefoxcreation/Codeformer-ONNX
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CodeFormerTranslator implements Translator<Image, Image> {

    private static final int INPUT_SIZE = 512;
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE, Image.Interpolation.BICUBIC);

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

        return ImageFactory.getInstance().fromNDArray(outputImg);
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
