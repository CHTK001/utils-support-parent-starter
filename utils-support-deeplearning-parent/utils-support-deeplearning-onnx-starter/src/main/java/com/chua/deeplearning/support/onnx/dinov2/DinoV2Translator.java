package com.chua.deeplearning.support.onnx.dinov2;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;


/**
 * DINOv2                    Translator
 * <p>
 * DINOv2     Meta                 Transformer                                                 
 *                                                                   
 * </p>
 *
 * @author CH
 * @since 2025-01-22
 */
@Slf4j
@Spi("dinov2")
public class DinoV2Translator implements Translator<Image, float[]> {

    /**
     * DINOv2                   
     */
    private static final int IMAGE_SIZE = 224;

    /**
     * ImageNet                
     */
    private static final float[] IMAGE_MEAN = {0.485f, 0.456f, 0.408f};

    /**
     * ImageNet                   
     */
    private static final float[] IMAGE_STD = {0.229f, 0.224f, 0.225f};


    /**
     *                   
     * <p>
     *                                                 
     * 1.                                      224   
     * 2.                 224x224
     * 3.              [0, 1]
     * 4.                                        
     * 5.           NCHW                            
     * </p>
     *
     * @param ctx                     
     * @param input             
     * @return              NDList                   [1, 3, 224, 224]          
     */
    @Override
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        //                            
        float percent = (float) IMAGE_SIZE / Math.min(input.getWidth(), input.getHeight());
        int resizedWidth = Math.round(input.getWidth() * percent);
        int resizedHeight = Math.round(input.getHeight() * percent);

        //        BICUBIC                   
        array = NDImageUtils.resize(array, resizedWidth, resizedHeight, Image.Interpolation.BICUBIC);

        //                            
        array = NDImageUtils.centerCrop(array, IMAGE_SIZE, IMAGE_SIZE);

        //                       FLOAT32
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }

        // HWC -> CHW                 [0, 1]
        array = array.transpose(2, 0, 1).div(255f);

        //             (x - mean) / std
        NDArray mean = ctx.getNDManager().create(IMAGE_MEAN, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(IMAGE_STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);

        //                    [C, H, W] -> [1, C, H, W]
        array = array.expandDims(0);

        return new NDList(array);
    }

    /**
     *                   
     * <p>
     *                                                       
     * DINOv2                                               768     1024          
     * </p>
     *
     * @param ctx                    
     * @param list                 NDList
     * @return                         
     */
    @Override
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();
        //                                           
        if (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }
        //                 [N, D]     patch                 DINOv2-Large     [257, 1024]      
        //                    [CLS] token             
        if (output.getShape().dimension() == 2) {
            output = output.get(0);
        }
        return output.toFloatArray();
    }

    /**
     *                   
     *
     * @return null                     
     */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}


