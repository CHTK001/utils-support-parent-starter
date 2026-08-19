package com.chua.deeplearning.support.tensorflow.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * TensorFlow 超分辨率 Translator。
 * <p>输入 FLOAT32 HWC；输出 clip 到 [0,255] 后还原 Image。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SuperResolutionTranslator implements Translator<Image, Image> {

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager()).toType(DataType.FLOAT32, false);
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.get(0).clip(0, 255).toType(DataType.UINT8, false);
        if (output.getShape().dimension() == 4 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }
        return ImageFactory.getInstance().fromNDArray(output);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
