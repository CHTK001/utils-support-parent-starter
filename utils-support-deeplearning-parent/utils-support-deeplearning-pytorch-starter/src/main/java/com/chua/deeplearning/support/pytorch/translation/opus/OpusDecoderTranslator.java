package com.chua.deeplearning.support.pytorch.translation.opus;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.pytorch.translation.CausalLMOutput;

/**
* Opus-MT 解码器 Translator。
*
* @author CH
* @since 4.0.0.42
 */
public class OpusDecoderTranslator implements NoBatchifyTranslator<NDList, CausalLMOutput> {

    /**
    * 层数。
    */
    private static final int NUM_LAYERS = 6;

    /**
    * 注意力头组数。
    */
    private static final int NUM_ATTENTION_HEADS = 4;

    /**
    * past_键_值 元组名。
    */
    private final String tupleName;

    /** 创建 opus解码器translator 实例 */
    public OpusDecoderTranslator() {
        this.tupleName = "past_key_values(" + NUM_LAYERS + ',' + NUM_ATTENTION_HEADS + ')';
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, NDList input) {
        NDArray placeholder = ctx.getNDManager().create(0);
        placeholder.setName("module_method:decoder");
        input.add(placeholder);
        return input;
    }

    @Override
    /** 处理输出 */
    public CausalLMOutput processOutput(TranslatorContext ctx, NDList output) {
        NDArray logitsOutput = output.get(0); // [P3C 四十一 豁免] NDArray 张量下标访问（非 List/Collection）
        NDList pastKeyValuesOutput = output.subNDList(1, NUM_LAYERS * NUM_ATTENTION_HEADS + 1);
        for (NDArray array : pastKeyValuesOutput) {
            array.setName(tupleName);
        }
        logitsOutput.detach();
        pastKeyValuesOutput.detach();
        return new CausalLMOutput(logitsOutput, pastKeyValuesOutput);
    }
}
