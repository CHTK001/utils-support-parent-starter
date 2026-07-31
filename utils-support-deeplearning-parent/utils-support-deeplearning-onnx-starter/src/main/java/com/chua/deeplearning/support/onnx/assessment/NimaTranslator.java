package com.chua.deeplearning.support.onnx.assessment;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * NIMA (Neural Image Assessment)                    Translator
 *
 * <p>       VGG16                                            10                          1-10          
 *        Softmax             ,                          Mean Opinion Score (MOS)   
 *
 * <p>         :
 * <ul>
 *   <li>          224x224
 *   <li>             [0, 1]          255.0   
 *   <li>ImageNet                                        
 *   <li>HWC     CHW       
 * </ul>
 *
 * <p>         :
 * <ul>
 *   <li>Softmax                            
 *   <li>       float[10]          1-10             
 * </ul>
 *
 * @author CH
 * @since 2026-05-09
 */
@Slf4j
public class NimaTranslator implements Translator<Image, float[]> {

    private static final int IMAGE_SIZE = 224;
    private static final float[] IMAGE_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] IMAGE_STD = {0.229f, 0.224f, 0.225f};

    @Override
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        //                            
        array = ai.djl.modality.cv.util.NDImageUtils.resize(array, IMAGE_SIZE, IMAGE_SIZE);

        //                       FLOAT32
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }

        // HWC     CHW + [0, 1]          
        array = array.transpose(2, 0, 1).div(255.0f);

        // ImageNet          
        NDArray mean = ctx.getNDManager().create(IMAGE_MEAN, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(IMAGE_STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);

        //                    [C, H, W]     [1, C, H, W]
        array = array.expandDims(0);

        return new NDList(array);
    }

    @Override
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();

        //                   
        if (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }

        // Softmax                                  
        NDArray exp = output.exp();
        NDArray softmax = exp.div(exp.sum());

        return softmax.toFloatArray();
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
