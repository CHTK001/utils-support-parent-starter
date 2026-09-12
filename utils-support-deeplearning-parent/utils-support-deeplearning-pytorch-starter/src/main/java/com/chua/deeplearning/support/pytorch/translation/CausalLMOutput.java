package com.chua.deeplearning.support.pytorch.translation;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;

/**
 * 因果语言模型输出。
 *
 * @param logits        logits
 * @param pastKeyValues past_键_值
 * @author CH
 * @since 4.0.0.42
 * @return Causallm输出的结果
 */
public record CausalLMOutput(NDArray logits, NDList pastKeyValues) {
}
