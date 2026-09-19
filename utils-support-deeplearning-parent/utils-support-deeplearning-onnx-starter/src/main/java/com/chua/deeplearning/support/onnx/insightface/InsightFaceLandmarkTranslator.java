package com.chua.deeplearning.support.onnx.insightface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.onnx.face.OnnxImageProcessor;

/**
 * 洞见face 2D 人脸关键点 Translator（buffalo_l 2d106det）。
 *
 * <p>192×192 RGB 输入（归一化 (rgb-127.5)/128），输出 212 维（106 关键点 × 2 坐标，
 * 归一化到 [0,1]）。关键点顺序：轮廓/眉毛/眼睛/鼻子/嘴，用于人脸对齐、美颜、换脸。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InsightFaceLandmarkTranslator implements Translator<Image, float[]> {

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
