package com.chua.deeplearning.support.onnx.generation;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
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
public class LcmLoraVaeDecoderTranslator implements Translator<NDList, Image> {

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
    public LcmLoraVaeDecoderTranslator() {
        this(512, 512);
    }

    /**
    *              -                
    *
    * @param width                    
    * @param height                   
     */
    public LcmLoraVaeDecoderTranslator(int width, int height) {
        this.width = width;
        this.height = height;
        if (log.isDebugEnabled()) {
            log.debug("[LCM-LoRA VAE Decoder][Translator]                -             : {}x{}", width, height);
        }
    }

    /**
    *                   
    *
    * @param ctx                     
    * @param input        nd列表
    * @return                         
     */
    @Override
    public NDList processInput(TranslatorContext ctx, NDList input) {
        // VAE                                           
        var modelInput = new NDList();

        //                          latent
        if (input.size() > 0) {
            var latent = input.get(0);
            modelInput.add(latent);
        }

        if (log.isDebugEnabled()) {
            log.debug("[LCM-LoRA VAE Decoder][Translator]                  : input size={}", modelInput.size());
        }

        return modelInput;
    }

    /**
    *                   
    *
    * @param ctx                    
    * @param list              nd列表
    * @return                   
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        try (NDManager manager = NDManager.newBaseManager(ctx.getNDManager().getDevice(), "PyTorch")) {
            var output = list.singletonOrThrow();

            //                               
            output = output.mul(0.5f).add(0.5f);

            //                   0-1         
            output = output.clip(0, 1);

            //          0-255                  UINT8      
            output = output.mul(255.0f).round().toType(DataType.UINT8, false);

            // CHW -> HWC
            output = output.transpose(1, 2, 0);

 // ndarray
            var img = ai.djl.modality.cv.ImageFactory.getInstance().fromNDArray(output);

            if (log.isDebugEnabled()) {
                log.debug("[LCM-LoRA VAE Decoder][Translator]                  : width={}, height={}", img.getWidth(), img.getHeight());
            }

            return img;
        }
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

