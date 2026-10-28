package com.chua.deeplearning.support.onnx.smoldocling;

import com.chua.common.support.collection.wrap.Wraps;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
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

    private static final int NUM_ATTENTION_HEADS = 9;

    /**
     * KV                
     */
    private static final int NUM_KEY_VALUE_HEADS = 3;

    private static final int HEAD_DIM = 64;

    /**
     * 处理输入
    */
    @Override
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
            log.warn("head_dim 与预期不符: hidden_size={}, inferred={}, preset={}",
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
        log.debug("预分配 past_key_values: {} 层", NUM_LAYERS);
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
                    log.warn("past_key_values 第 {} 层缺少 key 或 value", i);
                }
            }
        }

        log.debug("Decoder 输入: inputs_embeds={}, attention_mask={}, position_ids={}, past_key_values={} 层",
                inputsEmbeds.getShape(), attentionMask.getShape(), positionIds.getShape(), NUM_LAYERS);

        return inputs;
    }

    /**
     * 处理输出
    */
    @Override
    public DecoderStepOutput processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Decoder 模型输出 {} 个张量", list.size());
        }

        if (list.isEmpty()) {
            log.warn("Decoder 模型输出为空");
            return null;
        }

        //                    logits
        NDArray logits = list.getFirst();
        long[] logitsShape = logits.getShape().getShape();

        if (log.isDebugEnabled()) {
        log.debug("Decoder 输出: logits shape={}", logits.getShape());
        }

        //                             logits: [batch_size, seq_len, vocab_size] -> [batch_size, vocab_size]
        int seqLen = (int) logitsShape[1];
        NDArray lastLogits = logits.get(":, {}, :", seqLen - 1);

        //              logits           Java          
        long[] shape = lastLogits.getShape().getShape();
        float[] logitsData = lastLogits.toFloatArray();

                log.debug("Decoder 输出解析: logits shape={}, 注意力形状={}, 数据长度={}",
                logitsShape, shape, logitsData.length);

 // past_键_值
        //                logits + 30       (key, value) = 1 + 60 = 61          
        Map<String, NDArray> pastKeyValues = null;
        if (list.size() > 1) {
            pastKeyValues = Wraps.map();
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
        log.debug("预分配 past_key_values: {} 层", NUM_LAYERS);
                }
            } else {
            log.warn("past_key_values 输出数量不符: 期望 {}, 实际 {}", expectedOutputs, list.size());
            }
        }

        return new DecoderStepOutput(shape, logitsData, pastKeyValues);
    }

    /**
     * 获取Batchifier
    */
    @Override
    public Batchifier getBatchifier() {
        //                   
        return null;
    }

    /**
     * 解码器输入结构。
     *
     *
     * @author CH
     * @since 4.0.0
     */
    @Data
    public static class DecoderInput {
        private NDArray inputsEmbeds;

        private NDArray attentionMask;

        private NDArray positionIds;

        /**
         * Past 键 值   KV
         */
        private Map<String, NDArray> pastKeyValues;

        /**
         * 构造 DecoderInput 实例。
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
     * 解码器单步输出结构。
     *
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
         * 构造 DecoderStepOutput 实例。
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
         * 获取 VocabSize。
         *
         *
         * @return             
         */
        public int getVocabSize() {
            return (int) shape[shape.length - 1];
        }
    }
}
