package com.chua.deeplearning.support.pytorch.translation;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;

/**
 * NLLB 解码器 Translator（含 pastKeyValues）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NllbDecoderTranslator implements NoBatchifyTranslator<NDList, CausalLMOutput> {

    /**
     * 层数。
     */
    private static final int NUM_LAYERS = 12;

    /**
     * 注意力头组数。
     */
    private static final int NUM_KV = 4;

    /**
     * past_key_values 元组名。
     */
    private final String tupleName;

    public NllbDecoderTranslator() {
        this.tupleName = "past_key_values(" + NUM_LAYERS + ',' + NUM_KV + ')';
    }

    @Override
    public NDList processInput(TranslatorContext ctx, NDList input) {
        NDArray placeholder = ctx.getNDManager().create(0);
        placeholder.setName("module_method:decoder");
        input.add(placeholder);
        return input;
    }

    @Override
    public CausalLMOutput processOutput(TranslatorContext ctx, NDList output) {
        NDArray logitsOutput = output.get(0);
        NDList pastKeyValuesOutput = output.subNDList(1, NUM_LAYERS * NUM_KV + 1);
        for (NDArray array : pastKeyValuesOutput) {
            array.setName(tupleName);
        }
        return new CausalLMOutput(logitsOutput, pastKeyValuesOutput);
    }
}
