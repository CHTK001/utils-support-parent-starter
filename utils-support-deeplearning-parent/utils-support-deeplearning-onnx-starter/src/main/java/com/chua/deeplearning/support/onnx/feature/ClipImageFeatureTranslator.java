package com.chua.deeplearning.support.onnx.feature;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.NDArrayUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * CLIP                      
 * <p>
 *   CLIP ViT-B/16     ViT-B/32 Vision                               
 *     CLIP-ViT-B-16-IMAGE     CLIP-ViT-B-32-IMAGE          
 * </p>
 * <p>
 *                  
 * 1.                                224       
 * 2.                 224x224
 * 3.           RGB       
 * 4.              [0, 1]       
 * 5.                                        
 * 6.           NCHW       
 * </p>
 * <p>
 *                       OpenAI CLIP                      
 * - image_mean: [0.48145466, 0.4578275, 0.40821073]
 * - image_std: [0.26862954, 0.26130258, 0.27577711]
 * - crop_size: 224x224
 * - resample: BICUBIC
 * </p>
 *
 * @author CH
 * @since 2025-01-22
 */
@Slf4j
public class ClipImageFeatureTranslator implements Translator<Image, float[]> {

    /**
     * CLIP                            
     *   OpenAI CLIP                
     */
    private static final float[] IMAGE_MEAN = {0.48145466f, 0.4578275f, 0.40821073f};

    /**
     * CLIP                               
     *   OpenAI CLIP                
     */
    private static final float[] IMAGE_STD = {0.26862954f, 0.26130258f, 0.27577711f};

    /**
     *                         
     */
    private static final int IMAGE_SIZE = 224;

    /**
     *                
     * <p>
     * CLIP                                           
     * </p>
     *
     * @param ctx                   
     */
    @Override
    public void prepare(@Nonnull TranslatorContext ctx) {
        log.debug("[CLIP][      ] Translator             ");
    }

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
     * @throws Exception                         
     */
    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) throws Exception {
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
     * CLIP ViT-B/16     ViT-B/32           512                   
     * </p>
     *
     * @param ctx                    
     * @param list                 NDList
     * @return                         
     */
    @Override
    @Nonnull
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.get(0);

        //                                           
        if (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }
        return NDArrayUtils.safeToFloatArray(output);
    }

    /**
     *                   
     *
     * @return null                     
     */
    @Override
    @Nullable
    public Batchifier getBatchifier() {
        return null;
    }
}
