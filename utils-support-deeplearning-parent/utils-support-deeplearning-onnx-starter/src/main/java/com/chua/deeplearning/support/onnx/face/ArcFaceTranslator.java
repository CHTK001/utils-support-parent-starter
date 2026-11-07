package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;


/**
 * arcface (洞见face w600k_R50) 特征提取 Translator。
 *
 * <p>输入: [1, 3, 112, 112] RGB，归一化 (pixel - 127.5) / 128.0</p>
 * <p>输出: [1, 512] 512 维人脸特征</p>
 *
 * <p>预处理注意：ONNX Runtime 引擎的 NDArray 不支持 transpose/resize 等算子，
 * 因此统一走 {@link OnnxImageProcessor#toModelInput}（Java 侧像素处理 + 一次性建张量），
 * 归一化公式 (pixel - 127.5) / 128.0 对应 mean=127.5、scale=1/128。</p>
 *
 * <p>输出为 512 维原始特征，调用方可按需做 L2 归一化。</p>
 *
 * @author CH
 * @since 2025-01-20
 */
public class ArcFaceTranslator implements Translator<Image, float[]> {

    /**
     * 输入尺寸
    */
    private static final int INPUT_SIZE = 112;
    /**
     * 归一化均值
     */
    private static final float MEAN = 127.5f;
    /**
     * 归一化缩放
     */
    private static final float STD = 128.0f;

    /**
     * 处理输入
    */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        return OnnxImageProcessor.toModelInput(
                input, INPUT_SIZE, INPUT_SIZE,
                3, false, MEAN, 1.0f / STD,
                ctx.getNDManager());
    }

    /**
     * 处理输出
    */
    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        float[] features = array.toFloatArray();

        // L2 归一化
        float norm = 0.0f;
        for (float feature : features) {
            norm += feature * feature;
        }
        norm = (float) Math.sqrt(norm);

        if (norm > 0.0f) {
            for (int i = 0; i < features.length; i++) {
                features[i] = features[i] / norm;
            }
        }

        return features;
    }

    /**
     * 获取Batchifier
    */
    @Override
    public Batchifier getBatchifier() {
 // 输入已含 批量 维度 shape [1, C, H, W]，返回 空 避免 batchifier 二次堆叠
        return null;
    }
}

