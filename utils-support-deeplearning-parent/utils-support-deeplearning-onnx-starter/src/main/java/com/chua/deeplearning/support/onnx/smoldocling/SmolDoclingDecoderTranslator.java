package com.chua.deeplearning.support.onnx.smoldocling;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;


/**
 * smoldocling 解码器 Translator
 * <p>
 *                                        
 * <p>
 *                
 * - 输入_embeds:
 * - attention_mask:                
 * - 位置_标识:
 * - past_键_值:           KV
 * <p>
 *                
 * - logits:           令牌
 * - past_键_值:              KV
 *
 * @author CH
 * @since 2025/01/22
 */
@Slf4j
public class SmolDoclingDecoderTranslator implements Translator<SmolDoclingDecoderTranslator.DecoderInput, SmolDoclingDecoderTranslator.DecoderStepOutput> {

    /**
     *                                               30       
     */
    private static final int NUM_LAYERS = 30;

    /**
     *                   
     */
    private static final int NUM_ATTENTION_HEADS = 9;

    /**
     * KV                
     */
    private static final int NUM_KEY_VALUE_HEADS = 3;

    /**
     *                   
     */
    private static final int HEAD_DIM = 64;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, DecoderInput input) throws Exception {
        NDManager manager = ctx.getNDManager();

 // 输入_embeds: [批量_大小, seq_len, hidden_大小]
        NDArray inputsEmbeds = input.getInputsEmbeds();
        inputsEmbeds.setName("inputs_embeds");

 // attention_mask: [批量_大小, seq_len]
        NDArray attentionMask = input.getAttentionMask();
        attentionMask.setName("attention_mask");

 // 位置_标识: [批量_大小, seq_len]
        NDArray positionIds = input.getPositionIds();
        positionIds.setName("position_ids");

        NDList inputs = new NDList(inputsEmbeds, attentionMask, positionIds);

 // past_键_值
        Map<String, NDArray> pastKeyValues = input.getPastKeyValues();
        if (pastKeyValues == null || pastKeyValues.isEmpty()) {
 // past_键_值
 // 30                 键     值
 // past_键_值                    [批量_大小, num_heads, past_seq_len, head_dim]
            //                         past_seq_len = 0                                                
            long[] embedsShape = inputsEmbeds.getShape().getShape();
            int batchSize = (int) embedsShape[0];
            int hiddenSize = (int) embedsShape[2];

            int inferredHeadDim = hiddenSize / NUM_ATTENTION_HEADS;
            if (inferredHeadDim != HEAD_DIM) {
                log.warn("          head_dim                   : hidden_size={}, inferred={}, preset={}",
                        hiddenSize, inferredHeadDim, HEAD_DIM);
            }

            int headDim = HEAD_DIM;
            int kvHeads = NUM_KEY_VALUE_HEADS;

            //              past_key_values   past_seq_len = 0   
            for (int i = 0; i < NUM_LAYERS; i++) {
 // 键: [批量_大小, num_键_值_heads, 0, head_dim]
                NDArray pastKey = manager.zeros(new Shape(batchSize, kvHeads, 0, headDim));
                pastKey.setName("past_key_values." + i + ".key");
                inputs.add(pastKey);

 // 值: [批量_大小, num_键_值_heads, 0, head_dim]
                NDArray pastValue = manager.zeros(new Shape(batchSize, kvHeads, 0, headDim));
                pastValue.setName("past_key_values." + i + ".value");
                inputs.add(pastValue);
            }

            if (log.isDebugEnabled()) {
                log.debug("             past_key_values: {}    ", NUM_LAYERS);
            }
        } else {
 // past_键_值
 // layer0.键, layer0.值, layer1.键, layer1.值, ...
            for (int i = 0; i < NUM_LAYERS; i++) {
                NDArray key = pastKeyValues.get("past_key_values." + i + ".key");
                NDArray value = pastKeyValues.get("past_key_values." + i + ".value");
                if (key != null && value != null) {
                    key.setName("past_key_values." + i + ".key");
                    value.setName("past_key_values." + i + ".value");
                    inputs.add(key);
                    inputs.add(value);
                } else {
                    log.warn("past_key_values              {}     key     value", i);
                }
            }
        }

        log.debug("Decoder                   : inputs_embeds={}, attention_mask={}, position_ids={}, past_key_values={}    ",
                inputsEmbeds.getShape(), attentionMask.getShape(), positionIds.getShape(), NUM_LAYERS);

        return inputs;
    }

    @Override
    /** 处理输出 */
    public DecoderStepOutput processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("             Decoder             : {}          ", list.size());
        }

        if (list.isEmpty()) {
            log.warn("Decoder                   ");
            return null;
        }

        //                    logits
        NDArray logits = list.getFirst();
        long[] logitsShape = logits.getShape().getShape();

        if (log.isDebugEnabled()) {
            log.debug("Decoder             : logits shape={}", logits.getShape());
        }

        //                             logits: [batch_size, seq_len, vocab_size] -> [batch_size, vocab_size]
        int seqLen = (int) logitsShape[1];
        NDArray lastLogits = logits.get(":, {}, :", seqLen - 1);

        //              logits           Java          
        long[] shape = lastLogits.getShape().getShape();
        float[] logitsData = lastLogits.toFloatArray();

        log.debug("Decoder                      :       shape={},             shape={}, data length={}",
                logitsShape, shape, logitsData.length);

 // past_键_值
        //                logits + 30       (key, value) = 1 + 60 = 61          
        Map<String, NDArray> pastKeyValues = null;
        if (list.size() > 1) {
            pastKeyValues = new HashMap<>();
 // past_键_值                                      键     值
            int expectedOutputs = 1 + NUM_LAYERS * 2;
            if (list.size() >= expectedOutputs) {
                for (int i = 0; i < NUM_LAYERS; i++) {
                    int keyIndex = 1 + i * 2;
                    int valueIndex = 1 + i * 2 + 1;

                    NDArray key = list.get(keyIndex);
                    NDArray value = list.get(valueIndex);

 // nd管理器
                    NDManager externalManager = ctx.getNDManager();
                    NDArray keyCopied = key.toDevice(externalManager.getDevice(), true);
                    NDArray valueCopied = value.toDevice(externalManager.getDevice(), true);

                    pastKeyValues.put("past_key_values." + i + ".key", keyCopied);
                    pastKeyValues.put("past_key_values." + i + ".value", valueCopied);
                }
                if (log.isDebugEnabled()) {
                    log.debug("       past_key_values: {}    ", NUM_LAYERS);
                }
            } else {
                log.warn("past_key_values                      :        {},        {}", expectedOutputs, list.size());
            }
        }

        return new DecoderStepOutput(shape, logitsData, pastKeyValues);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        //                   
        return null;
    }

    /**
     *                
     * @author CH
     * @since 4.0.0
     */
    @Data
    public static class DecoderInput {
        /**
         *                                                       
         */
        private NDArray inputsEmbeds;

        /**
         *                
         */
        private NDArray attentionMask;

        /**
         *             
         */
        private NDArray positionIds;

        /**
         * Past 键 值   KV
         */
        private Map<String, NDArray> pastKeyValues;

        /**
         *             
         *
         * @param inputsEmbeds              
         * @param attentionMask                
         * @param positionIds               
         */
        public DecoderInput(NDArray inputsEmbeds, NDArray attentionMask, NDArray positionIds) {
            this.inputsEmbeds = inputsEmbeds;
            this.attentionMask = attentionMask;
            this.positionIds = positionIds;
            this.pastKeyValues = null;
        }

        /**
         *                    KV          
         *
         * @param inputsEmbeds              
         * @param attentionMask                
         * @param positionIds               
         * @param pastKeyValues KV       
         */
        public DecoderInput(NDArray inputsEmbeds, NDArray attentionMask, NDArray positionIds,
                            Map<String, NDArray> pastKeyValues) {
            this.inputsEmbeds = inputsEmbeds;
            this.attentionMask = attentionMask;
            this.positionIds = positionIds;
            this.pastKeyValues = pastKeyValues;
        }
    }

    /**
     *                      
     * @author CH
     * @since 4.0.0
     */
    @Data
    public static class DecoderStepOutput {
        /**
         * logits       
         */
        private long[] shape;

        /**
         * logits       
         */
        private float[] logits;

        /**
         *              KV                   
         */
        private Map<String, NDArray> pastKeyValues;

        /**
         *             
         *
         * @param shape  logits       
         * @param logits logits       
         */
        public DecoderStepOutput(long[] shape, float[] logits) {
            this.shape = shape;
            this.logits = logits;
            this.pastKeyValues = null;
        }

        /**
         *                    KV          
         *
         * @param shape        logits       
         * @param logits       logits       
         * @param pastKeyValues              KV       
         */
        public DecoderStepOutput(long[] shape, float[] logits, Map<String, NDArray> pastKeyValues) {
            this.shape = shape;
            this.logits = logits;
            this.pastKeyValues = pastKeyValues;
        }

        /**
         *                   
         *
         * @return             
         */
        public int getVocabSize() {
            return (int) shape[shape.length - 1];
        }
    }
}
