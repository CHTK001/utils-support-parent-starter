package com.chua.deeplearning.support.onnx.text;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;


/**
 * BERT-squad
 * <p>
 * : 令牌_标识, attention_mask, segment_标识
 * : 启动_logits, 结束_logits
 * <p>
 *          :
 * -        BERT tokenizer                                  
 * -        令牌_标识, attention_mask, segment_标识
 * <p>
 *          :
 * -     启动_logits     结束_logits
 * -                   
 *
 * @author CH
 * @since 2025-01-20
 */
@Slf4j
public class BertSquadTranslator implements Translator<Map<String, String>, String> {

    /**
     * BERT-squad
     */
    private static final int MAX_LENGTH = 384;

    /**
     * BERT [CLS] 令牌 标识
     */
    private static final int CLS_TOKEN_ID = 101;

    /**
     * BERT [SEP] 令牌 标识
     */
    private static final int SEP_TOKEN_ID = 102;

    /**
     * BERT [UNK] 令牌 标识
     */
    private static final int UNK_TOKEN_ID = 100;

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, Map<String, String> input) {
        NDManager manager = ctx.getNDManager();
        
        String question = input.getOrDefault("question", "");
        String context = input.getOrDefault("context", "");
        
        //           tokenization                      BERT tokenizer   
        //                                           
        String[] questionTokens = question.toLowerCase().split("\\s+");
        String[] contextTokens = context.toLowerCase().split("\\s+");
        
 // : [CLS] question [SEP] 上下文 [SEP]
        int maxTokens = Math.min(MAX_LENGTH - 2, questionTokens.length + contextTokens.length + 3);
        long[] tokenIds = new long[maxTokens];
        long[] attentionMask = new long[maxTokens];
        long[] segmentIds = new long[maxTokens];
        
        int pos = 0;
        tokenIds[pos] = CLS_TOKEN_ID;
        attentionMask[pos] = 1;
        segmentIds[pos] = 0;
        pos++;
        
 // 令牌
        for (int i = 0; i < questionTokens.length && pos < maxTokens - 1; i++) {
            tokenIds[pos] = hashToken(questionTokens[i]);
            attentionMask[pos] = 1;
            segmentIds[pos] = 0;
            pos++;
        }
        
        tokenIds[pos] = SEP_TOKEN_ID;
        attentionMask[pos] = 1;
        segmentIds[pos] = 0;
        pos++;
        
 // 令牌
        for (int i = 0; i < contextTokens.length && pos < maxTokens - 1; i++) {
            tokenIds[pos] = hashToken(contextTokens[i]);
            attentionMask[pos] = 1;
            segmentIds[pos] = 1;
            pos++;
        }
        
        tokenIds[pos] = SEP_TOKEN_ID;
        attentionMask[pos] = 1;
        segmentIds[pos] = 1;
        pos++;
        
        //                      
        while (pos < maxTokens) {
            tokenIds[pos] = 0;
            attentionMask[pos] = 0;
            segmentIds[pos] = 0;
            pos++;
        }
        
        NDArray tokenIdsArray = manager.create(tokenIds).expandDims(0);
        NDArray attentionMaskArray = manager.create(attentionMask).expandDims(0);
        NDArray segmentIdsArray = manager.create(segmentIds).expandDims(0);
        
        return new NDList(tokenIdsArray, attentionMaskArray, segmentIdsArray);
    }

    @Override
    /**
     * 处理输出
    */
    public String processOutput(TranslatorContext ctx, NDList list) {
        if (list.size() < 2) {
            return "";
        }
        
        NDArray startLogits = list.getFirst();
        NDArray endLogits = list.get(1);
        
        float[] startScores = startLogits.toFloatArray();
        float[] endScores = endLogits.toFloatArray();
        
        //                                        
        int startIdx = 0;
        int endIdx = 0;
        float maxScore = Float.NEGATIVE_INFINITY;
        
        for (int i = 0; i < startScores.length; i++) {
            for (int j = i; j < endScores.length && j < i + 30; j++) {
                float score = startScores[i] + endScores[j];
                if (score > maxScore) {
                    maxScore = score;
                    startIdx = i;
                    endIdx = j;
                }
            }
        }
        
 // 令牌_标识
        return String.format("            : %d-%d (      : %.2f)", startIdx, endIdx, maxScore);
    }

    /**
     * 令牌 哈希                             BERT vocab
     *
     * @param token                   
     * @return                             BERT vocab 标识
     */
    private long hashToken(String token) {
        if (StringUtils.isEmpty(token)) {
            return UNK_TOKEN_ID;
        }
        
 // 哈希                      BERT vocab
        long hash = token.hashCode();
        return Math.abs(hash % 30000) + 100;
    }

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
