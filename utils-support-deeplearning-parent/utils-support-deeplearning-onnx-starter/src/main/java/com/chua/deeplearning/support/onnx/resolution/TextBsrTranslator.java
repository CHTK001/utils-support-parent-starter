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
import lombok.extern.slf4j.Slf4j;


/**
 * TextBSR                            
 * <p>
 *                                                             
 *                                                
 * </p>
 * <p>
 *                
 * -                                   
 * -                          
 * -                          
 * </p>
 * <p>
 *                   
 * 1.                                        
 * 2.              [0, 1]
 * 3.           CHW       
 * 4.                
 * </p>
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025/01/26
 */
@Slf4j
public class TextBsrTranslator implements Translator<Image, Image> {

    /**
     * ND               
     */
    private NDManager manager;

    /**
     *                
     */
    private final int detectResolution = 512;

    /**
     *             
     *
     * @param ctx                   
     */
    @Override
    public void prepare(TranslatorContext ctx) {
        this.manager = NDManager.newBaseManager(ctx.getNDManager().getDevice(), "PyTorch");
        if (log.isDebugEnabled()) {
            log.debug("[TextBSR][Translator]               ");
        }
    }

    /**
     *                   
     *
     * @param ctx                     
     * @param input             
     * @return              NDList
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        //                   NDArray                        FLOAT32
        NDArray array = input.toNDArray(this.manager).toType(DataType.FLOAT32, false);

        //                      
        float upScale = (float) detectResolution / (float) input.getHeight();

        //                                              
        int resizedWidth = (int) (upScale * input.getWidth());

        //                   
        array = NDImageUtils.resize(array, resizedWidth, detectResolution);

        //                            0-1      
        array = array.transpose(2, 0, 1).div(255f);

        //                                  
        NDArray mean = ctx.getNDManager().create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));

        //                                     
        NDArray std = ctx.getNDManager().create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));

        //                         (array - mean) / std
        array.subi(mean).divi(std);

        if (log.isDebugEnabled()) {
            log.debug("[TextBSR][Translator]                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }

        return new NDList(array);
    }

    /**
     *                   
     *
     * @param ctx                    
     * @param list              NDList
     * @return                         
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        //                         
        NDArray outputImg = list.singletonOrThrow();

        //                output * 0.5 + 0.5
        outputImg = outputImg.mul(0.5f).add(0.5f);

        //                   0-1         
        outputImg = outputImg.clip(0, 1);

        //          0-255                  UINT8      
        outputImg = outputImg.mul(255.0f).round().toType(DataType.UINT8, false);

        //    NDArray                  
        Image img = ImageFactory.getInstance().fromNDArray(outputImg);

        //             
        this.manager.close();

        if (log.isDebugEnabled()) {
            log.debug("[TextBSR][Translator]                  : width={}, height={}", img.getWidth(), img.getHeight());
        }

        return img;
    }

    /**
     *                   
     *
     * @return STACK             
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
