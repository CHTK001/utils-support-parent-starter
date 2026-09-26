package com.chua.deeplearning.support.onnx.text;

/**
 * tokenizer                       
 * <p>
 * 令牌 ids         attention mask
 * <p>
 * 输入标识: 令牌 ids
 * attentionmask:                     mask
 * valid令牌数量:                 令牌
 * sequence长度: 输入标识
 * 是否valid:
 *
 * @author CH
 * @since 2024-11-14
 */
public record TokenizerResult(
        long[] inputIds,

        long[] attentionMask,

        int validTokenCount
) {

    /**
     * 规范构造器：两个数组做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。</p>
     *
     * <p>两个数组刻意保留 空 语义：本 record 的 {@link #getSequenceLength()} 显式写过
     * {@code inputIds != null ? inputIds.length : 0}，说明既有调用方允许空数组，
     * 改成拒绝 空 值就是改语义。</p>
     *
     * @param inputIds 令牌 ids
     * @param attentionMask attention mask
     */
    public TokenizerResult {
        inputIds = inputIds == null ? null : inputIds.clone();
        attentionMask = attentionMask == null ? null : attentionMask.clone();
    }

    /**
     * 访问器覆写：返回内部数组的副本。
     *
     * <p>value class 前置条件——数组组件必须深不可变。</p>
     *
     * @return 令牌 ids 副本；组件为 空 时返回 空
     */
    @Override
    public long[] inputIds() {
        return inputIds == null ? null : inputIds.clone();
    }

    /**
     * 访问器覆写：返回内部数组的副本。
     *
     * <p>value class 前置条件——数组组件必须深不可变。</p>
     *
     * @return attention mask 副本；组件为 空 时返回 空
     */
    @Override
    public long[] attentionMask() {
        return attentionMask == null ? null : attentionMask.clone();
    }

    /**
     * 输入标识
     *
     * @return inputIds
     */
    public long[] getInputIds() {
        return inputIds == null ? null : inputIds.clone();
    }

    /**
     * attentionmask                     mask
     *
     * @return attentionMask
     */
    public long[] getAttentionMask() {
        return attentionMask == null ? null : attentionMask.clone();
    }

    /**
     * 令牌
     *
     * @return validTokenCount         
     */
    public int getValidTokenCount() {
        return validTokenCount;
    }

    /**
     * 输入标识
     *
     * @return                     
     */
    public int getSequenceLength() {
        return inputIds != null ? inputIds.length : 0;
    }

    /**
     *                   
     *
     * @return true                       
     */
    public boolean isValid() {
        return validTokenCount > 0;
    }
}
