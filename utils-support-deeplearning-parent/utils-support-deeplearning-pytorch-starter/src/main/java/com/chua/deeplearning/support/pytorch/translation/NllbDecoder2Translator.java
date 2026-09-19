package com.chua.deeplearning.support.pytorch.translation;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;

/**
 * NLLB 解码器第二阶段 Translator（带 past键值 续写）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NllbDecoder2Translator implements NoBatchifyTranslator<NDList, CausalLMOutput> {

    /**
     * 层数。
     */
    private static final int NUM_LAYERS = 12;

    /**
     * 注意力头组数。
     */
    private static final int NUM_KV = 4;

    /**
     * past_键_值 元组名。
     */
    private final String tupleName;

    /** 创建 nllb解码器2Translator 实例 */
    public NllbDecoder2Translator() {
        this.tupleName = "past_key_values(" + NUM_LAYERS + ',' + NUM_KV + ')';
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, NDList input) {
        NDArray placeholder = ctx.getNDManager().create(0);
        placeholder.setName("module_method:decoder2");
        input.add(placeholder);
        return input;
    }

    @Override
    /** 处理输出 */
    public CausalLMOutput processOutput(TranslatorContext ctx, NDList output) {
        NDArray logitsOutput = output.get(0); // [P3C 四十一 豁免] NDArray 张量下标访问（非 List/Collection）
        NDList pastKeyValuesOutput = output.subNDList(1, NUM_LAYERS * NUM_KV + 1);
        for (NDArray array : pastKeyValuesOutput) {
            array.setName(tupleName);
        }
        return new CausalLMOutput(logitsOutput, pastKeyValuesOutput);
    }
}
