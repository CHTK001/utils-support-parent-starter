package com.chua.deeplearning.support.onnx.insightface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.onnx.face.OnnxImageProcessor;

/**
* 洞见face adaface 人脸识别 Translator（buffalo_l adaface）。
*
* <p>112×112 RGB 输入（归一化 (rgb-127.5)/128），输出 512 维 embedding（未归一化，
* 调用方按需 L2 归一化）。Rnet IR50 骨干 + adaface 自适应 margin。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class InsightFaceAdaFaceTranslator implements Translator<Image, float[]> {

    /** 输入尺寸 */
    private static final int INPUT_SIZE = 112;

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
