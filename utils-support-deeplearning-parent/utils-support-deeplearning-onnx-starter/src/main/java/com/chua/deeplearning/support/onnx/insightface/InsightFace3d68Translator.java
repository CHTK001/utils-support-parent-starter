package com.chua.deeplearning.support.onnx.insightface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.onnx.face.OnnxImageProcessor;

/**
 * 洞见face 1K3D68 3D 人脸关键点 Translator（buffalo_l 1k3d68）。
 *
 * <p>192×192 RGB 输入（归一化 (rgb-127.5)/128），输出 3309 维（1103 顶点 × 3 坐标
 * x/y/z，归一化到 [0,1]，z 为深度）。用于 3D 人脸重建、姿态估计。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InsightFace3d68Translator implements Translator<Image, float[]> {

    /**
     * 输入尺寸
    */
    private static final int INPUT_SIZE = 192;

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, Image input) {
        return OnnxImageProcessor.toModelInput(input, INPUT_SIZE, INPUT_SIZE, 3, false,
                127.5f, 1.0f / 128.0f, ctx.getNDManager());
    }

    @Override
    /**
     * 处理输出
    */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        return array.toFloatArray();
    }

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return null;
    }
}
