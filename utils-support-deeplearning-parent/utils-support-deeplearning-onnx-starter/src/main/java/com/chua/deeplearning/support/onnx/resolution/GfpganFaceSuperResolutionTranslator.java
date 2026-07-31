package com.chua.deeplearning.support.onnx.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;


/**
 * GFPGAN                            
 * <p>
 *              GFPGAN                                              
 * GFPGAN                                                 
 * <p>
 *                
 * -           float32       
 * -              [0, 1]
 * -             mean=[0.5, 0.5, 0.5], std=[0.5, 0.5, 0.5]
 * -           CHW       
 * <p>
 *                
 * -           [-1, 1]       
 * -                 [0, 255]
 * -           UINT8       
 * -           Image       
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2024/11/08
 */
public class GfpganFaceSuperResolutionTranslator implements Translator<Image, Image> {

    /**
     *                      
     */
    private static final int[] MIN_MAX = new int[]{-1, 1};

    /**
     *                
     */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};

    /**
     *                   
     */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};
    private static final int INPUT_SIZE = 512;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        NDManager manager = ctx.getNDManager();

        //           float32       
        NDArray array = input.toNDArray(manager).toType(DataType.FLOAT32, false);

        // GFPGAN v1.4 ONNX                 512x512             translator                
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE);

        //           CHW                       [0, 1]
        array = array.transpose(2, 0, 1).div(255.0f);

        //        mean     std       
        NDArray mean = manager.create(MEAN, new Shape(3, 1, 1));
        NDArray std = manager.create(STD, new Shape(3, 1, 1));

        //          : (x - mean) / std
        array = array.sub(mean).div(std);

        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) throws Exception {
        //                   
        NDArray array = list.get(0);

        //           [-1, 1]       
        array = array.clip(MIN_MAX[0], MIN_MAX[1]);

        //             : ((x - min) / (max - min)) * 255
        //     [-1, 1]           [0, 255]
        array = array.sub(MIN_MAX[0])
                .div(MIN_MAX[1] - MIN_MAX[0])
                .mul(255.0f);

        //             
        array = array.round();

        //           UINT8
        array = array.toType(DataType.UINT8, false);

        //           Image
        return ImageFactory.getInstance().fromNDArray(array);
    }

    @Override
    public Batchifier getBatchifier() {
        //        STACK             
        return Batchifier.STACK;
    }
}

