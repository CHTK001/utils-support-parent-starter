package com.chua.deeplearning.support.onnx.smoldocling;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
* smoldocling
* <p>
* smoldocling
* </p>
* <p>
*                
* 1.              -> Vision ->             
* 2.              -> Embed ->             
* 3.              +              -> 解码器 ->
* </p>
* <p>
*                
* -                          
* -                          vision   embed   解码器
* -                          
* </p>
* <p>
*                                                                   
* Translator           smoldocling
* </p>
*
* @author CH
* @版本 4.0.0.32
* @since 2025/01/26
 */
@Slf4j
public class SmolDoclingCombinedTranslator implements Translator<SmolDoclingCombinedTranslator.CombinedInput, String> {

    /**
    * Vision          
    */
    private SmolDoclingVisionTranslator visionTranslator;

    /**
    * Embed          
    */
    private SmolDoclingEmbedTranslator embedTranslator;

    /**
    * 解码器
    */
    private SmolDoclingDecoderTranslator decoderTranslator;

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
        visionTranslator = new SmolDoclingVisionTranslator();
        embedTranslator = new SmolDoclingEmbedTranslator();
        decoderTranslator = new SmolDoclingDecoderTranslator();

        //                      
        try {
            visionTranslator.prepare(ctx);
            embedTranslator.prepare(ctx);
            decoderTranslator.prepare(ctx);
        } catch (Exception e) {
            throw new IOException("                           ", e);
        }

        if (log.isDebugEnabled()) {
            log.debug("[SmolDocling Combined][Translator]               ");
        }
    }

    /**
    *                   
    * <p>
    *                                   Vision                   
    * </p>
    *
    * @param ctx                     
    * @param input                                        
    * @return Vision              nd列表
    * @throws Exception             
    */
    @Override
    public NDList processInput(TranslatorContext ctx, CombinedInput input) throws Exception {
        //        Vision                            
        //                                        
        ctx.setAttachment("text_input", input.getTextTokens());
        return visionTranslator.processInput(ctx, input.getImage());
    }

    /**
    *                   
    * <p>
    *                                                             
    *                                                 
    * </p>
    *
    * @param ctx                    
    * @param list              nd列表          Vision
    * @return                         
    * @throws Exception             
    */
    @Override
    public String processOutput(TranslatorContext ctx, NDList list) throws Exception {
        //                                           
        //                
        // 1.        -> vision ->                list   
        // 2.        -> embed ->             
        // 3.              +              -> decoder ->                   

        if (list == null || list.isEmpty()) {
            log.warn("[SmolDocling Combined][Translator]Vision                   ");
            return "";
        }

        //                               
        var textTokens = (long[]) ctx.getAttachment("text_input");
        if (textTokens == null) {
            log.warn("[SmolDocling Combined][Translator]                        ");
            return "";
        }

        //        Vision       
        var visionOutput = visionTranslator.processOutput(ctx, list);
        if (visionOutput == null) {
            log.warn("[SmolDocling Combined][Translator]Vision                   ");
            return "";
        }

        //        Embed       
        var embedInput = embedTranslator.processInput(ctx, textTokens);
        //                                embed             
        // var embedOutput = embedTranslator.processOutput(ctx, embedModelOutput);

        //                                  
 // 解码器

        if (log.isDebugEnabled()) {
            log.debug("[SmolDocling Combined][Translator]                  :              shape={}",
                    visionOutput.getShapeString());
        }

 // 解码器
        return "";
    }

    /**
    *                   
    *
    * @return null                        
    */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    /**
    *             
    * @author CH
    * @since 4.0.0
    */
    @Data
    public static class CombinedInput {
        /**
        *             
        */
        private Image image;

        /**
        * 令牌 ids
        */
        private long[] textTokens;

        /**
        *             
        *
        * @param image                  
        * @param textTokens        令牌 标识
        */
        public CombinedInput(Image image, long[] textTokens) {
            this.image = image;
            this.textTokens = textTokens;
        }
    }
}
