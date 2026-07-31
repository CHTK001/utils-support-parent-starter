package com.chua.deeplearning.support.onnx.realwebphoto;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;


/**
 * 4xRealWebPhoto_v4                            
 * <p>
 *              RealWebPhoto                                              
 *                                                                                     
 * <p>
 *                
 * -           float32       
 * -              [0, 1]
 * -           CHW          Channel, Height, Width   
 * -        batch          [1, C, H, W]
 * <p>
 *                
 * -        batch          [1, C, H, W]     [C, H, W]
 * -           [0, 1]       
 * -           [0, 255]
 * -           UINT8       
 * -           Image       
 * <p>
 *                
 * -        ESRGAN       
 * -                                     
 * -     JPEG                                              
 * -                                        
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2024/11/08
 */
@Slf4j
public class RealWebPhotoTranslator implements Translator<Image, Image> {

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("                        : {}x{}", input.getWidth(), input.getHeight());
        }

        NDManager manager = ctx.getNDManager();

        //           float32       
        NDArray array = input.toNDArray(manager).toType(DataType.FLOAT32, false);

        //              [0, 1]
        // HWC              CHW                   
        array = array.transpose(2, 0, 1).div(255.0f);

        //        batch       : [C, H, W]     [1, C, H, W]
        array = array.expandDims(0);

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("                        ");
        }

        //                   
        NDArray array = list.get(0);
        if (log.isDebugEnabled()) {
            log.debug("       shape: {}, dtype: {}", array.getShape(), array.getDataType());
        }

        //        batch       : [1, C, H, W]     [C, H, W]
        array = array.squeeze(0);

        //           [0, 1]       
        array = array.clip(0.0f, 1.0f);

        //           [0, 255]
        array = array.mul(255.0f);

        //             
        array = array.round();

        //           UINT8
        array = array.toType(DataType.UINT8, false);

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }

        //           Image
        return ImageFactory.getInstance().fromNDArray(array);
    }

    @Override
    public Batchifier getBatchifier() {
        //           batchifier                         processInput                    batch       
        return null;
    }
}

