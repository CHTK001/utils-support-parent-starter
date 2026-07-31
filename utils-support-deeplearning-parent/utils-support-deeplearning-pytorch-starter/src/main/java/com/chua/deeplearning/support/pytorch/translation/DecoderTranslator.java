package com.chua.deeplearning.support.pytorch.translation;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;

/**
 * 通用解码器 Translator（含 pastKeyValues）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DecoderTranslator implements NoBatchifyTranslator<NDList, CausalLMOutput> {

    /**
     * past_key_values 元组名。
     */
    private final String tupleName;

    public DecoderTranslator() {
        this.tupleName = "past_key_values(" + 12 + ',' + 4 + ')';
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
        NDList pastKeyValuesOutput = output.subNDList(1, 12 * 4 + 1);
        for (NDArray array : pastKeyValuesOutput) {
            array.setName(tupleName);
        }
        return new CausalLMOutput(logitsOutput, pastKeyValuesOutput);
    }
}
