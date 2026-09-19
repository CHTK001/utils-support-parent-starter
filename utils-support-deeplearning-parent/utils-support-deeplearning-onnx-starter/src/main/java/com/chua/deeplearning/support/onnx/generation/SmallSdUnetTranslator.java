package com.chua.deeplearning.support.onnx.generation;

import ai.djl.ndarray.NDList;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Small st Diffusion v0 unet
 * <p>
 * Small st Diffusion v0           unet
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
 * -          nd列表        [latent, timestep, 文本_嵌入]
 *   - latent:                      shape: [1, 4, H/8, W/8]   
 *   - timestep:             shape: [1]   
 * - 文本_嵌入:                      shape: [1, 77, 768]
 * -                                           shape: [1, 4, H/8, W/8]   
 * </p>
 *
 * @author CH
 * @since 2025-01-30
 */
@Slf4j
public class SmallSdUnetTranslator implements NoBatchifyTranslator<NDList, NDList> {

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
    public SmallSdUnetTranslator() {
        this(512, 512);
    }

    /**
     *              -                
     *
     * @param width                    
     * @param height                   
     */
    public SmallSdUnetTranslator(int width, int height) {
        this.width = width;
        this.height = height;
        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][UNet]                 -             : {}x{}", width, height);
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
            log.debug("[Small SD v0][UNet] Translator             ");
        }
    }

    /**
     *                   
     * <p>
     * unet
     * - 样本:                      latent
     * - timestep:          
     * - 编码器_hidden_状态:
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
            latent.setName("sample");
            modelInput.add(latent);
        }

        //                   timestep               
        if (input.size() > 1) {
            var timestep = input.get(1);
            timestep.setName("timestep");
            modelInput.add(timestep);
        }

 // 文本_嵌入
        if (input.size() > 2) {
            var textEmbeddings = input.get(2);
            textEmbeddings.setName("encoder_hidden_states");
            modelInput.add(textEmbeddings);
        }

        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][UNet]                   : input size={}", modelInput.size());
        }

        return modelInput;
    }

    /**
     *                   
     * <p>
     * unet
     * - noise_pred:                   shape: [1, 4, H/8, W/8]   
     * </p>
     *
     * @param ctx                    
     * @param list                   
     * @return                               
     */
    @Override
    public NDList processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        var unetOutput = new NDList();

        //                                        
        if (list.size() > 0) {
            var noisePred = list.getFirst();
            unetOutput.add(noisePred);
        }

        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][UNet]                   : output shape={}", 
                    list.size() > 0 ? list.getFirst().getShape() : "empty");
        }

        return unetOutput;
    }
}

