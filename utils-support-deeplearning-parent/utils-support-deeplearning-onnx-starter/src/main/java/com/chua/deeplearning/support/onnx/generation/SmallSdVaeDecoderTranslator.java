package com.chua.deeplearning.support.onnx.generation;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
* Small Stable Diffusion v0 VAE                   
* <p>
*              Small Stable Diffusion v0           VAE                
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
* -                               shape: [1, 4, H/8, W/8]   
* -                               shape: [1, 3, H, W]   
* </p>
*
* @author CH
* @since 2025-01-30
 */
@Slf4j
public class SmallSdVaeDecoderTranslator implements NoBatchifyTranslator<NDList, Image> {

    /**
    *                   
    */
    private final int width;

    /**
    *                   
    */
    private final int height;

    /**
    * VAE                Stable Diffusion        0.18215   
    */
    private static final float VAE_SCALE_FACTOR = 0.18215f;

    /**
    *              -                   
    */
    public SmallSdVaeDecoderTranslator() {
        this(512, 512);
    }

    /**
    *              -                
    *
    * @param width                    
    * @param height                   
    */
    public SmallSdVaeDecoderTranslator(int width, int height) {
        this.width = width;
        this.height = height;
        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][VAE Decoder]                 -             : {}x{}", width, height);
        }
    }

    /**
    *             
    *
    * @param ctx                   
    */
    @Override
    public void prepare(@Nonnull TranslatorContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][VAE Decoder] Translator             ");
        }
    }

    /**
    *                   
    * <p>
    * VAE                         
    * - latent:                   
    * </p>
    *
    * @param ctx                     
    * @param input        nd列表
    * @return                         
    */
    @Override
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull NDList input) {
        var modelInput = new NDList();

        //                   latent                        
        if (input.size() > 0) {
            var latent = input.getFirst();
            
            //        VAE                Stable Diffusion                
            latent = latent.div(VAE_SCALE_FACTOR);
            latent.setName("latent_sample");
            modelInput.add(latent);
        }

        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][VAE Decoder]                   : input shape={}", 
                    input.size() > 0 ? input.getFirst().getShape() : "empty");
        }

        return modelInput;
    }

    /**
    *                   
    * <p>
    * VAE                   
    * - 样本:                            shape: [1, 3, H, W]
    * </p>
    *
    * @param ctx                    
    * @param list              nd列表
    * @return                   
    */
    @Override
    public Image processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        var manager = ctx.getNDManager();
        var output = list.singletonOrThrow();

        //        batch          NCHW -> CHW
        if (output.getShape().dimension() == 4) {
            output = output.squeeze(0);
        }

        //                    [-1, 1]     [0, 1]
        output = output.add(1.0f).div(2.0f);

        //                    [0, 1]          
        output = output.clip(0.0f, 1.0f);

        //           [0, 255]                    UINT8       
        output = output.mul(255.0f).round().toType(DataType.UINT8, false);

        // CHW -> HWC
        if (output.getShape().dimension() == 3) {
            output = output.transpose(1, 2, 0);
        }

 // ndarray
        var img = ImageFactory.getInstance().fromNDArray(output);

        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][VAE Decoder]                   : width={}, height={}", 
                    img.getWidth(), img.getHeight());
        }

        return img;
    }
}

