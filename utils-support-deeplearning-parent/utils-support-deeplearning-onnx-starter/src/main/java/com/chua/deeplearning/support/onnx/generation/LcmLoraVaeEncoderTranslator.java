package com.chua.deeplearning.support.onnx.generation;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;



/**
* LCM-lora VAE
* <p>
* LCM-lora           VAE
*                                     
* </p>
* <p>
*                
* -                       
* -                       
* -                       
* </p>
*
* @author CH
* @版本 4.0.0.32
* @since 2025/01/26
 */
@Slf4j
public class LcmLoraVaeEncoderTranslator implements Translator<Image, NDList> {

    /**
    *                   
     */
    private final int width;

    /**
    *                   
     */
    private final int height;

    /**
    *              -                   
     */
    public LcmLoraVaeEncoderTranslator() {
        this(512, 512);
    }

    /**
    *              -                
    *
    * @param width                    
    * @param height                   
     */
    public LcmLoraVaeEncoderTranslator(int width, int height) {
        this.width = width;
        this.height = height;
        if (log.isDebugEnabled()) {
            log.debug("[LCM-LoRA VAE Encoder][Translator]                -             : {}x{}", width, height);
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
        var manager = ctx.getNDManager();
        var array = input.toNDArray(manager, Image.Flag.COLOR);

        //                      
        array = NDImageUtils.resize(array, width, height);

        //        float32                 0~1
        array = array.toType(DataType.FLOAT32, false).div(255f);

        // HWC -> CHW
        array = array.transpose(2, 0, 1);

        //                    [1, C, H, W]
        array = array.expandDims(0);

        if (log.isDebugEnabled()) {
            log.debug("[LCM-LoRA VAE Encoder][Translator]                  : shape={}", array.getShape());
        }

        return new NDList(array);
    }

    /**
    *                   
    *
    * @param ctx                    
    * @param list              nd列表
    * @return VAE                                        
     */
    @Override
    public NDList processOutput(TranslatorContext ctx, NDList list) {
        // VAE                                           
        var output = new NDList();

        //                          latent
        if (list.size() > 0) {
            var latent = list.get(0);
            output.add(latent);
        }

        if (log.isDebugEnabled()) {
            log.debug("[LCM-LoRA VAE Encoder][Translator]                  : output size={}", output.size());
        }

        return output;
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

