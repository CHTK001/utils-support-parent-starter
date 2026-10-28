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
     * 初始化。
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
            throw new IOException("SmolDocling 组合翻译器初始化失败", e);
        }

        if (log.isDebugEnabled()) {
            log.debug("[SmolDocling Combined][Translator] 初始化完成");
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
     * 执行 Output。
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
            log.warn("[SmolDocling Combined][Translator]Vision 输出为空");
            return "";
        }

        //                               
        var textTokens = (long[]) ctx.getAttachment("text_input");
        if (textTokens == null) {
            log.warn("[SmolDocling Combined][Translator] 未获取到文本输入 token");
            return "";
        }

        //        Vision       
        var visionOutput = visionTranslator.processOutput(ctx, list);
        if (visionOutput == null) {
            log.warn("[SmolDocling Combined][Translator]Vision 输出处理失败");
            return "";
        }

        //        Embed       
        var embedInput = embedTranslator.processInput(ctx, textTokens);
        //                                embed             
        // var embedOutput = embedTranslator.processOutput(ctx, embedModelOutput);

        //                                  
 // 解码器

        if (log.isDebugEnabled()) {
            log.debug("[SmolDocling Combined][Translator] 视觉特征形状: shape={}",
                    visionOutput.getShapeString());
        }

 // 解码器
        return "";
    }

    /**
     * 获取 Batchifier。
     *                   
     *
     * @return null                        
     */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 组合推理的输入结构。
     *
     *             
     * @author CH
     * @since 4.0.0
     */
    @Data
    public static class CombinedInput {
        private Image image;

        /**
         * 令牌 ids
         */
        private long[] textTokens;

        /**
         * 构造 CombinedInput 实例。
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
