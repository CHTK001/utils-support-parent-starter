// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.onnx.insightface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.onnx.face.OnnxImageProcessor;

/**
 * 洞见face 性别年龄预测 Translator（buffalo_l genderage）。
 *
 * <p>96×96 RGB 输入（归一化 (rgb-127.5)/128），输出 3 维：</p>
 * <ul>
 *   <li>[0] 女性概率（softmax 前 logit）</li>
 *   <li>[1] 男性概率（softmax 前 logit）</li>
 *   <li>[2] 年龄（0~100 归一化到 ~[0,1]，乘 100 得年龄）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InsightFaceGenderAgeTranslator implements Translator<Image, float[]> {

    /**
     * 输入尺寸
    */
    private static final int INPUT_SIZE = 96;

    /**
     * 处理输入
    */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        return OnnxImageProcessor.toModelInput(input, INPUT_SIZE, INPUT_SIZE, 3, false,
                127.5f, 1.0f / 128.0f, ctx.getNDManager());
    }

    /**
     * 处理输出
    */
    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        return array.toFloatArray();
    }

    /**
     * 获取Batchifier
    */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}