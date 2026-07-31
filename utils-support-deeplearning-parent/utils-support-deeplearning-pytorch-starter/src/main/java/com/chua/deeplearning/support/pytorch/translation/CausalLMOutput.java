package com.chua.deeplearning.support.pytorch.translation;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;

/**
 * 因果语言模型输出。
 *
 * @param logits        logits
 * @param pastKeyValues past_key_values
 * @author CH
 * @since 4.0.0.42
 */
public record CausalLMOutput(NDArray logits, NDList pastKeyValues) {
}
