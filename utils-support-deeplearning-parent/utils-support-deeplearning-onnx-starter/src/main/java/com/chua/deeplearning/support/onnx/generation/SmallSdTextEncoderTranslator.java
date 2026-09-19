package com.chua.deeplearning.support.onnx.generation;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.annotation.Nonnull;

/**
 * Small Stable Diffusion v0                         
 * <p>
 *              Small Stable Diffusion v0                               
 *                                                       
 * </p>
 * <p>
 *                
 * -                       
 * -            CLIP                
 * -                       
 * </p>
 * <p>
 *                      
 * -                         
 * -                             nd列表   shape: [1, 77, 768]     [1, 768]
 * </p>
 *
 * @author CH
 * @since 2025-01-30
 */
@Slf4j
public class SmallSdTextEncoderTranslator implements NoBatchifyTranslator<String, NDList> {

    /**
     * CLIP                                  
     */
    private static final int MAX_SEQUENCE_LENGTH = 77;

    /**
     * huggingface
     */
    private HuggingFaceTokenizer tokenizer;

    /**
     *             
     * <p>
     *                             tokenizer.json
     * </p>
     *
     * @param ctx                   
     * @throws IOException IO      
     */
    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws IOException {
        try {
            var model = ctx.getModel();
            var modelPath = model.getModelPath();
            
            if (modelPath == null) {
                log.warn("[Small SD v0][Text Encoder]                                   tokenizer");
                return;
            }

            //                                         tokenizer.json
            var tokenizerPath = Paths.get(modelPath.toString(), "tokenizer.json");
            if (!Files.exists(tokenizerPath)) {
                var parentPath = modelPath.getParent();
                if (parentPath != null) {
                    tokenizerPath = Paths.get(parentPath.toString(), "tokenizer.json");
                }
            }

            if (Files.exists(tokenizerPath)) {
                tokenizer = HuggingFaceTokenizer.builder()
                        .optTokenizerPath(tokenizerPath)
                        .optPadding(true)
                        .optMaxLength(MAX_SEQUENCE_LENGTH)
                        .build();
                log.debug("[Small SD v0][Text Encoder] Translator                Tokenizer             : {}", tokenizerPath);
            } else {
                log.warn("[Small SD v0][Text Encoder]           tokenizer.json         : {}", tokenizerPath);
            }
        } catch (Exception e) {
            log.error("[Small SD v0][Text Encoder] Tokenizer             ", e);
            throw new IOException("Tokenizer             : " + e.getMessage(), e);
        }
    }

    /**
     *                   
     * <p>
     * Small Stable Diffusion v0                               
     * - 输入_标识: 令牌 标识          shape: [1, 77]
     * </p>
     *
     * @param ctx                     
     * @param input             
     * @return                         
     * @throws Exception             
     */
    @Override
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull String input) throws Exception {
        if (tokenizer == null) {
            throw new IllegalStateException("HuggingFaceTokenizer                                                     tokenizer.json");
        }

        //        Tokenizer             
        Encoding encoding = tokenizer.encode(input);
        long[] tokenIds = encoding.getIds();

 // 输入_标识
        var inputIds = ctx.getNDManager().create(tokenIds);
        if (!inputIds.getDataType().equals(DataType.INT64)) {
            inputIds = inputIds.toType(DataType.INT64, false);
        }
        //                    [sequence_length] -> [1, sequence_length]
        inputIds = inputIds.expandDims(0);
        inputIds.setName("input_ids");

        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][Text Encoder]                   : input size={}, sequence length={}", 
                    inputIds.getShape(), tokenIds.length);
        }

        return new NDList(inputIds);
    }

    /**
     *                   
     * <p>
     * Small Stable Diffusion v0                         
     * - 最后一个_hidden_状态:                         shape: [1, 77, 768]
     * -     游泳池_输出:                shape: [1, 768]
     * </p>
     *
     * @param ctx                    
     * @param list                   
     * @return                 NDList
     * @throws Exception             
     */
    @Override
    public NDList processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) throws Exception {
        var encoderOutput = new NDList();

 // 最后一个_hidden_状态     游泳池_输出
        if (list.size() > 0) {
            var output = list.getFirst();
            encoderOutput.add(output);
        }

        if (log.isDebugEnabled()) {
            log.debug("[Small SD v0][Text Encoder]                   : output shape={}", 
                    list.size() > 0 ? list.getFirst().getShape() : "empty");
        }

        return encoderOutput;
    }
}

