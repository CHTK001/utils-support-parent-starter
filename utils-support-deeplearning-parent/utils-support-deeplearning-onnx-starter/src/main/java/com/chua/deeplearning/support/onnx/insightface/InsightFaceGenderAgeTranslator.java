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

    /** 输入尺寸 */
    private static final int INPUT_SIZE = 96;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        return OnnxImageProcessor.toModelInput(input, INPUT_SIZE, INPUT_SIZE, 3, false,
                127.5f, 1.0f / 128.0f, ctx.getNDManager());
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        return array.toFloatArray();
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
