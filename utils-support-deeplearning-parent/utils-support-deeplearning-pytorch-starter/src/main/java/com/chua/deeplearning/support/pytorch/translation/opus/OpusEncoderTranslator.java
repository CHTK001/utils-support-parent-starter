package com.chua.deeplearning.support.pytorch.translation.opus;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;

import java.util.Arrays;

/**
 * Opus-MT 编码器 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpusEncoderTranslator implements NoBatchifyTranslator<int[], NDArray> {

    @Override
    public NDList processInput(TranslatorContext ctx, int[] input) {
        NDManager manager = ctx.getNDManager();
        NDArray inputIdArray = manager.create(input).expandDims(0).toType(DataType.INT64, false);
        inputIdArray.setName("input_ids");

        long[] attentionMask = new long[input.length];
        Arrays.fill(attentionMask, 1);
        NDArray attentionMaskArray = manager.create(attentionMask).expandDims(0);
        attentionMaskArray.setName("attention_mask");

        NDArray placeholder = manager.create(0);
        placeholder.setName("module_method:encoder");
        return new NDList(inputIdArray, attentionMaskArray, placeholder);
    }

    @Override
    public NDArray processOutput(TranslatorContext ctx, NDList list) {
        NDArray encoderHiddenStates = list.get(0);
        encoderHiddenStates.detach();
        return encoderHiddenStates;
    }
}
