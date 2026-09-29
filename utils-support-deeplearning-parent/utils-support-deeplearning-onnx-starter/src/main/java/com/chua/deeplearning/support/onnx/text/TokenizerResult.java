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
 * <p>三个组件的长度关系（配合 {@code getSequenceLength()} 读取）：</p>
 * <ul>
 *   <li>{@code getSequenceLength()} 返回 {@link #inputIds} 的长度，即补齐后的定长序列长度；</li>
 *   <li>{@link #attentionMask} 与 {@link #inputIds} 逐位一一对应，长度必须相等
 *       （ONNX Runtime 的 {@code attention_mask} 输入与 ids 同形）；</li>
 *   <li>{@link #validTokenCount} 是 {@code attentionMask} 中置 1 的个数，
 *       即未被 padding 填充的真实 token 数，满足
 *       {@code 0 <= validTokenCount <= inputIds.length}，
 *       {@code isValid()} 即等价于 {@code validTokenCount > 0}。</li>
 * </ul>
 *
 * @param inputIds        分词后的 token ID 数组，已按模型要求的定长序列补齐
 *                        （尾部 padding，padding ID 一般为 0）；元素为词表内的非负 ID。
 *                        允许为 {@code null}——紧凑构造器显式保留空语义
 *                        （{@code getSequenceLength()} 已对 {@code null} 兜底返回 0）；
 *                        允许为长度为 0 的空数组，此时序列长度为 0。
 *                        构造与访问器都做 {@code clone()}，外部改动不影响内部
 * @param attentionMask   注意力掩码数组，与 {@link #inputIds} 长度相同且逐位对应：
 *                        真实 token 位为 1、padding 位为 0，喂给 ONNX 模型屏蔽补齐位。
 *                        允许为 {@code null}（紧凑构造器保留空语义），同样做 {@code clone()} 防御性拷贝
 * @param validTokenCount 有效 token 数量（个），即 {@link #attentionMask} 中 1 的个数，
 *                        不含 padding 位；取值范围 {@code 0 <= validTokenCount <= inputIds.length}，
 *                        为 0 时 {@code isValid()} 返回 {@code false}，调用方应先判空再用。
 *                        本组件是 {@code int} 基本类型，构造时不校验上界
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
