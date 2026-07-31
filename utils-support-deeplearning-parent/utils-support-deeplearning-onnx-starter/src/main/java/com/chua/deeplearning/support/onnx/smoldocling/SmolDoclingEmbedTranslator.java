package com.chua.deeplearning.support.onnx.smoldocling;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;


/**
 * SmolDocling Embed Translator
 * <p>
 *                 token IDs                      
 * <p>
 *                
 * -     token IDs           NDArray
 * -        attention_mask
 * <p>
 *                
 * -                   
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025/01/22
 */
@Slf4j
public class SmolDoclingEmbedTranslator implements Translator<long[], SmolDoclingEmbedOutput> {

    @Override
    public NDList processInput(TranslatorContext ctx, long[] input) throws Exception {
        NDManager manager = ctx.getNDManager();

        //        input_ids NDArray
        // Shape: [batch_size, sequence_length] = [1, seq_len]
        NDArray inputIds = manager.create(input).expandDims(0);
        inputIds.setName("input_ids");

        if (log.isDebugEnabled()) {
            log.debug("Embed                   : input_ids shape={}", inputIds.getShape());
        }

        return new NDList(inputIds);
    }

    @Override
    public SmolDoclingEmbedOutput processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("             Embed             : {}          ", list.size());
        }

        if (list.isEmpty()) {
            log.warn("Embed                   ");
            return null;
        }

        //                   
        NDArray embeddings = list.get(0);
        long[] outputShape = embeddings.getShape().getShape();

        if (log.isDebugEnabled()) {
            log.debug("Embed             : embeddings shape={}", embeddings.getShape());
        }

        //                          NDArray           float                                  
        return new SmolDoclingEmbedOutput(embeddings);
    }

    @Override
    public Batchifier getBatchifier() {
        //                   
        return null;
    }
}
