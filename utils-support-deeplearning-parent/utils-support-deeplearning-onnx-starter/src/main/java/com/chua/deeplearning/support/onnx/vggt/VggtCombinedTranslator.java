package com.chua.deeplearning.support.onnx.vggt;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * VGGT                   
 * <p>
 * VGGT                                                        3D                   
 * </p>
 * <p>
 *                
 * 1.              -> VGGT       -> 3D            
 * </p>
 * <p>
 *                
 * -               3D       
 * -               3D                   
 * </p>
 * <p>
 *          VGGT                          Translator                               
 * </p>
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025/01/26
 */
@Slf4j
public class VggtCombinedTranslator implements Translator<Image, VggtOutput> {

    /**
     *                                        
     */
    private VggtOutputTranslator outputTranslator;

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
        outputTranslator = new VggtOutputTranslator();
        try {
            outputTranslator.prepare(ctx);
        } catch (Exception e) {
            throw new IOException("                           ", e);
        }

        if (log.isDebugEnabled()) {
            log.debug("[VGGT Combined][Translator]               ");
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
     * @return              NDList
     * @throws Exception             
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        return outputTranslator.processInput(ctx, input);
    }

    /**
     *                   
     * <p>
     *                          VggtOutput       
     * </p>
     *
     * @param ctx                    
     * @param list              NDList
     * @return VggtOutput       
     * @throws Exception             
     */
    @Override
    public VggtOutput processOutput(TranslatorContext ctx, NDList list) throws Exception {
        return outputTranslator.processOutput(ctx, list);
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
}
