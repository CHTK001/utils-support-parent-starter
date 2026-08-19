package com.chua.deeplearning.support.onnx.animegan;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;


/**
 * AnimeGANv3                
 * <p>
 *        AnimeGANv3                                           
 *
 *                
 * 1.                                                    
 * 2.                                                       
 * 3.                                  
 *
 * @author CH
 * @version 4.0.0.30
 * @since 2024/12/20
 */
@Slf4j
public class AnimeGanV3Translator implements Translator<Image, Image> {

    /**
     *                   
     */
    private static final int INPUT_SIZE = 512;

    /**
     *                                           
     */
    private int originalWidth;
    /** 原始高度 */
    private int originalHeight;

    /**
     *                      
     *
     * @param ctx                   
     * @param input             
     * @return                            
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        //                   
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3                               : {}x{}", originalWidth, originalHeight);
        }

        //           NDArray (HWC       )
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        if (log.isDebugEnabled()) {
            log.debug("Step 1 -                : {}", array.getShape());
        }

        //                             512x512
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE, Image.Interpolation.BICUBIC);
        if (log.isDebugEnabled()) {
            log.debug("Step 2 -           512x512          : {}", array.getShape());
        }

        //                       [-1, 1]
        array = array.div(255.0f).mul(2.0f).sub(1.0f);
        if (log.isDebugEnabled()) {
            log.debug("Step 3 -                   : {}", array.getShape());
        }

        //           3     (HWC)
        if (array.getShape().dimension() == 2) {
            array = array.expandDims(-1);
            if (log.isDebugEnabled()) {
                log.debug("Step 3.5 -                            : {}", array.getShape());
            }
        }

        //                       HWC 3      
        if (array.getShape().dimension() != 3) {
            log.error("ERROR:        3D                               : {}", array.getShape());
            throw new IllegalArgumentException("Invalid array dimension for transpose: " + array.getShape());
        }

        //                    3
        if (array.getShape().get(2) != 3) {
            log.warn("WARNING:        3                      : {}", array.getShape().get(2));
        }

        //                       HWC     NHWC                               HWC          
        array = array.expandDims(0);
        if (log.isDebugEnabled()) {
            log.debug("Step 4 -                            : {}", array.getShape());
        }

        //           float32
        array = array.toType(DataType.FLOAT32, false);
        if (log.isDebugEnabled()) {
            log.debug("Step 5 -             : {} (       [1, 512, 512, 3])", array.getShape());
        }

        //                   
        if (array.getShape().dimension() != 4) {
            throw new IllegalArgumentException(
                    "Invalid output shape: expected 4D (NHWC), got " + array.getShape().dimension() + "D"
           );
        }

        Shape shape = array.getShape();
        if (shape.get(0) != 1 || shape.get(1) != INPUT_SIZE || shape.get(2) != INPUT_SIZE || shape.get(3) != 3) {
            log.warn("AnimeGANv3                                     :        [1, {}, {}, 3],       : {}",
                    INPUT_SIZE, INPUT_SIZE, shape);
        }

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3                               : NHWC [batch=1, height={}, width={}, channels=3]", INPUT_SIZE, INPUT_SIZE);
        }

        return new NDList(array);
    }

    /**
     *                      
     *
     * @param ctx                   
     * @param list                         
     * @return                      
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3                               : {}", output.getShape());
        }

        //                    NHWC -> HWC
        if (output.getShape().dimension() == 4) {
            output = output.squeeze(0);
            if (log.isDebugEnabled()) {
                log.debug("                           : {}", output.getShape());
            }
        }

        //                    [-1, 1]     [0, 255]
        output = output.add(1.0f).mul(127.5f);
        if (log.isDebugEnabled()) {
            log.debug("                     : {}", output.getShape());
        }

        //                    [0, 255]
        output = output.clip(0, 255);

        //                       uint8
        output = output.toType(DataType.UINT8, false);
        if (log.isDebugEnabled()) {
            log.debug("                           : {} (       HWC       )", output.getShape());
        }

        //                 HWC                         

        //             
        Image result = ImageFactory.getInstance().fromNDArray(output);

        //                      
        if (result.getWidth() != originalWidth || result.getHeight() != originalHeight) {
            if (log.isDebugEnabled()) {
                log.debug("                      {}x{}     {}x{}", result.getWidth(), result.getHeight(), originalWidth, originalHeight);
            }
            NDArray resizedArray = NDImageUtils.resize(
                    result.toNDArray(ctx.getNDManager()),
                    originalWidth,
                    originalHeight,
                    Image.Interpolation.BICUBIC
           );
            result = ImageFactory.getInstance().fromNDArray(resizedArray);
        }

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv3                               : {}x{}", result.getWidth(), result.getHeight());
        }

        return result;
    }

    /**
     *                   
     *
     * @return              -        NONE                    processInput                               
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.fromString("none");
    }

    /**
     *                   
     *
     * @return             
     */
    public int getInputSize() {
        return INPUT_SIZE;
    }

    /**
     *                         
     *
     * @return                    [width, height]
     */
    public int[] getOriginalSize() {
        return new int[]{originalWidth, originalHeight};
    }
}
