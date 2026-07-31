package com.chua.deeplearning.support.onnx.generation;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Random;

/**
 * Small Stable Diffusion v0                   
 * <p>
 *        Small Stable Diffusion v0                                                             
 * </p>
 * <p>
 *                
 * 1.              -> TextEncoder -> text_embeddings
 * 2.                    latent
 * 3.              UNet                     text_embeddings + latent -> UNet -> denoised_latent
 * 4.        latent -> VAEDecoder ->             
 * </p>
 * <p>
 *                
 * -                                   
 * -                          text_encoder   unet   vae_decoder   
 * -                       
 * -                                      
 * </p>
 *
 * @author CH
 * @since 2025-01-30
 */
@Slf4j
public class SmallStableDiffusionCombinedTranslator implements Translator<String, Image> {

    /**
     *                         
     */
    private SmallSdTextEncoderTranslator textEncoderTranslator;

    /**
     * UNet          
     */
    private SmallSdUnetTranslator unetTranslator;

    /**
     * VAE                   
     */
    private SmallSdVaeDecoderTranslator vaeDecoderTranslator;

    /**
     *                   
     */
    private final int width;

    /**
     *                   
     */
    private final int height;

    /**
     *                       20       Small SD v0                            
     */
    private final int numInferenceSteps;

    /**
     *                                                 
     */
    private final Random random;

    /**
     *              -                            
     */
    public SmallStableDiffusionCombinedTranslator() {
        this(512, 512, 20);
    }

    /**
     *              -                         
     *
     * @param width                               
     * @param height                              
     * @param numInferenceSteps                   
     */
    public SmallStableDiffusionCombinedTranslator(int width, int height, int numInferenceSteps) {
        this.width = width;
        this.height = height;
        this.numInferenceSteps = numInferenceSteps;
        this.random = new Random();
    }

    /**
     *             
     * <p>
     *                                  
     * </p>
     *
     * @param ctx                   
     * @throws IOException IO      
     */
    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        textEncoderTranslator = new SmallSdTextEncoderTranslator();
        unetTranslator = new SmallSdUnetTranslator(width, height);
        vaeDecoderTranslator = new SmallSdVaeDecoderTranslator(width, height);

        //                      
        try {
            textEncoderTranslator.prepare(ctx);
            unetTranslator.prepare(ctx);
            vaeDecoderTranslator.prepare(ctx);
        } catch (Exception e) {
            throw new IOException("                           ", e);
        }

        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][Translator]                -             : {}x{},             : {}", width, height, numInferenceSteps);
        }
    }

    /**
     *                         
     * <p>
     *                                                    
     * </p>
     *
     * @param ctx                     
     * @param input             
     * @return                       NDList
     * @throws Exception             
     */
    @Override
    public NDList processInput(TranslatorContext ctx, String input) throws Exception {
        //                                     
        //                                              
        var textEncoderInput = textEncoderTranslator.processInput(ctx, input);
        
        //                                                             
        ctx.setAttachment("text_encoder_input", textEncoderInput);
        ctx.setAttachment("prompt", input);

        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][Translator]                  : prompt length={}", input.length());
        }

        return textEncoderInput;
    }

    /**
     *                   
     * <p>
     *                                                 
     *                                  
     * </p>
     *
     * @param ctx                    
     * @param list              NDList                           
     * @return                
     * @throws Exception             
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) throws Exception {
        var manager = ctx.getNDManager();

        // 1.                                        
        var textEmbeddingsList = textEncoderTranslator.processOutput(ctx, list);
        var textEmbeddings = textEmbeddingsList.singletonOrThrow();
        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][Translator]                  : embeddings shape={}", textEmbeddings.getShape());
        }

        // 2.                    latent                        
        // Small SD v0                                               1/8
        var latentHeight = height / 8;
        var latentWidth = width / 8;
        // = 4; // Stable Diffusion        
        var latentChannels = 4;
        var latentShape = new Shape(1, latentChannels, latentHeight, latentWidth);
        //                                                        0             1
        //        RandomUtils                                NDArray
        var totalSize = (int) latentShape.size();
        var randomData = new float[totalSize];
        for (int i = 0; i < totalSize; i++) {
            randomData[i] = (float) (random.nextGaussian() * 1.0);
        }
        var latent = manager.create(randomData, latentShape);

        // 3.                                   UNet             
        var timesteps = generateTimesteps(numInferenceSteps);
        
        for (int i = 0; i < timesteps.length; i++) {
            var timestep = timesteps[i];
            var timestepArray = manager.create(new long[]{timestep});

            //        UNet          latent + timestep + text_embeddings
            var unetInput = new NDList();
            unetInput.add(latent);
            unetInput.add(timestepArray);
            unetInput.add(textEmbeddings);

            //        UNet             
            //                                                             
            //                                        
            // 1.                                                     unet.onnx   
            // 2.                                  
            // 3.                                                    
            //                                                                                                          
            var unetInputProcessed = unetTranslator.processInput(ctx, unetInput);
            // TODO:                                                        UNet                   
            //                                                                                     
            var unetOutputList = unetTranslator.processOutput(ctx, new NDList());
            var noisePred = unetOutputList.isEmpty() ? latent : unetOutputList.singletonOrThrow();
            
            //        DDIM                 latent
            // DDIM                   x_{t-1} = sqrt(alpha_{t-1}) * pred_x0 + sqrt(1 - alpha_{t-1}) * noise_pred
            //                                     alpha     1.0           0.0
            var currentAlpha = 1.0f - (float) i / numInferenceSteps;
            var nextAlpha = i < numInferenceSteps - 1 ? 1.0f - (float) (i + 1) / numInferenceSteps : 0.0f;
            
            //              latent   pred_x0 = (latent - sqrt(1 - alpha) * noise_pred) / sqrt(alpha)
            var sqrtOneMinusAlpha = (float) Math.sqrt(1.0 - currentAlpha);
            var sqrtAlpha = (float) Math.sqrt(currentAlpha);
            var predX0 = latent.sub(noisePred.mul(sqrtOneMinusAlpha)).div(sqrtAlpha);
            
            //                    latent   x_{t-1} = sqrt(alpha_{t-1}) * pred_x0 + sqrt(1 - alpha_{t-1}) * noise_pred
            var sqrtNextAlpha = (float) Math.sqrt(nextAlpha);
            var sqrtOneMinusNextAlpha = (float) Math.sqrt(1.0 - nextAlpha);
            latent = predX0.mul(sqrtNextAlpha).add(noisePred.mul(sqrtOneMinusNextAlpha));

            if (log.isDebugEnabled() && i % 5 == 0) {
                log.debug("[Small SD v0][Translator]             {}/{}       ", i + 1, numInferenceSteps);
            }
        }

        // 4. VAE                                              
        //                                                             
        //                                        
        // 1.                                                     vae_decoder.onnx   
        // 2.                                  
        // 3.                                                    
        //                                                                                                          
        var vaeInput = new NDList(latent);
        var vaeInputProcessed = vaeDecoderTranslator.processInput(ctx, vaeInput);
        // TODO:                                                        VAE                            
        //                                                                                     
        var vaeOutputRaw = new NDList();
        var generatedImage = vaeDecoderTranslator.processOutput(ctx, vaeOutputRaw);

        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][Translator]                  : width={}, height={}", generatedImage.getWidth(), generatedImage.getHeight());
        }

        return generatedImage;
    }

    /**
     *                      
     * <p>
     *                                           
     * </p>
     *
     * @param numSteps       
     * @return                                  
     */
    private long[] generateTimesteps(int numSteps) {
        var timesteps = new long[numSteps];
        //                    1000     0
        for (int i = 0; i < numSteps; i++) {
            timesteps[i] = 1000L - (i * 1000L / numSteps);
        }
        return timesteps;
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

