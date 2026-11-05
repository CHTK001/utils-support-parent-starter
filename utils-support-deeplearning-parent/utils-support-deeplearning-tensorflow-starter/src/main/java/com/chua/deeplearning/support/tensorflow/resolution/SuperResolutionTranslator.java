// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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
 * tensor流 超分辨率 Translator。
 * <p>输入 FLOAT32 HWC；输出 clip 到 [0,255] 后还原 Image。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SuperResolutionTranslator implements Translator<Image, Image> {

    /**
     * 处理输入
    */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager()).toType(DataType.FLOAT32, false);
        return new NDList(array);
    }

    /**
     * 处理输出
    */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.getFirst().clip(0, 255).toType(DataType.UINT8, false);
        // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        if (output.getShape().dimension() == 4 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }
        return ImageFactory.getInstance().fromNDArray(output);
    }

    /**
     * 获取Batchifier
    */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}