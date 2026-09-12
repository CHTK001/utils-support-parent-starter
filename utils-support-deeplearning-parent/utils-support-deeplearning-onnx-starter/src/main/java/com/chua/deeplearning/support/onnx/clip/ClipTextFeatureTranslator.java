package com.chua.deeplearning.support.onnx.clip;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.NDArrayUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
* CLIP                      
* <p>
* CLIP                                     
* CLIP-vit-B-16-文本     CLIP-vit-B-32-文本
* </p>
* <p>
*                  
* 1.        huggingface Tokenizer
* 2.     令牌 ids
* 3.                                     77       
* </p>
* <p>
*                            
* -          输入_标识 (shape: [批量_大小, sequence_长度], 最大_长度=77)
* -          文本_embeds (shape: [批量_大小, 512])
* </p>
* <p>
* CLIP-vit-B-16-文本     CLIP-vit-B-32-文本
*    512                                                                   
* </p>
*
* @author CH
* @since 2025-01-22
 */
@Slf4j
public class ClipTextFeatureTranslator implements Translator<String, float[]> {

    /**
    * CLIP                                  
    *   CLIP Vision                               77
     */
    private static final int MAX_SEQUENCE_LENGTH = 77;

    /**
    * 令牌 ids
     */
    private static final long DEFAULT_PAD_TOKEN_ID = 1L;

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
    * @throws IOException              tokenizer       
     */
    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws IOException {
        try {
            var model = ctx.getModel();
            var modelPath = model.getModelPath();

            if (modelPath == null) {
                log.warn("[CLIP][      ]                                   tokenizer");
                return;
            }

            var tokenizerPath = Paths.get(modelPath.toString(), "tokenizer.json");
            if (!Files.exists(tokenizerPath)) {
                //                         
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
                log.debug("[CLIP][      ] Translator                Tokenizer             : {}", tokenizerPath);
            } else {
                log.warn("[CLIP][      ]           tokenizer.json         : {}", tokenizerPath);
            }
        } catch (Exception e) {
            log.error("[CLIP][      ] Tokenizer             ", e);
            throw new IOException("Tokenizer             : " + e.getMessage(), e);
        }
    }

    /**
    *                   
    * <p>
    *                                                 
    * 1.        Tokenizer                      
    * 2.        令牌 ids
    * 3.                                              
    * </p>
    *
    * @param ctx                     
    * @param input             
    * @return              NDList                   [1, sequence_长度]     输入_标识
    * @throws Exception                   
     */
    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull String input) throws Exception {
        if (tokenizer == null) {
            throw new IllegalStateException("HuggingFaceTokenizer                                                     tokenizer.json");
        }

        //        Tokenizer             
        Encoding encoding = tokenizer.encode(input);
        long[] tokenIds = normalizeTokenIds(encoding.getIds());

 // 输入_标识
 // ONNX                       [批量_大小, sequence_长度]
        var inputIds = ctx.getNDManager().create(tokenIds);
        //                       INT64               
        if (!inputIds.getDataType().equals(DataType.INT64)) {
            inputIds = inputIds.toType(DataType.INT64, false);
        }
        //                    [sequence_length] -> [1, sequence_length]
        inputIds = inputIds.expandDims(0);
        inputIds.setName("input_ids");

        return new NDList(inputIds);
    }

    /**
    *                   
    * <p>
    *                                                       
    * CLIP vit-B/16     vit-B/32                       512
    * </p>
    *
    * @param ctx                    
    * @param list                 nd列表          文本_embeds
    * @return                            512       
     */
    @Override
    @Nonnull
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = selectEmbeddingOutput(list);

        //                                           
        if (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }

        return NDArrayUtils.safeToFloatArray(output);
    }

    /**
    *                                      
    * <p>
    * : 文本_embeds / 游泳池_输出 / sentence_嵌入
    *                                     
    * </p>
    *
    * @param list nd列表
    * @return                               
     */
    private NDArray selectEmbeddingOutput(NDList list) {
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException("CLIP                         ");
        }

        for (String preferredName : new String[]{"text_embeds", "pooler_output", "sentence_embedding"}) {
            for (NDArray output : list) {
                if (preferredName.equalsIgnoreCase(output.getName())) {
                    return output;
                }
            }
        }

        for (NDArray output : list) {
            if (output.getShape().dimension() == 2) {
                return output;
            }
        }

        return list.getLast();
    }

    /**
    *                   
    *
    * @return null                     
     */
    @Override
    @Nullable
    public Batchifier getBatchifier() {
        return null;
    }

    /**
    * 令牌 ids
    *
    * @param tokenIds                  
    * @return                            MAX_SEQUENCE_LENGTH       
     */
    private long[] normalizeTokenIds(long[] tokenIds) {
        if (tokenIds == null || tokenIds.length == 0) {
            long[] padded = new long[MAX_SEQUENCE_LENGTH];
            Arrays.fill(padded, DEFAULT_PAD_TOKEN_ID);
            return padded;
        }

        if (tokenIds.length == MAX_SEQUENCE_LENGTH) {
            return tokenIds;
        }

        long[] normalized = new long[MAX_SEQUENCE_LENGTH];
        Arrays.fill(normalized, DEFAULT_PAD_TOKEN_ID);
        System.arraycopy(tokenIds, 0, normalized, 0, Math.min(tokenIds.length, MAX_SEQUENCE_LENGTH));
        return normalized;
    }
}
