package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
* faceplugin face landmark detection Translator (mobilefacenet).
*
* <p>Model: MobileFaceNet
* 输入:  1x1x64x64 grayscale, normalized by /256
* 输出: 136-dim (68 landmarks * 2 coordinates), 值 入 [0, 1] relative 转为 cropped face
*
* <p>The caller must crop the face region from the image using the detected bounding box
* 之前 通过 it 转为 this translator. The 136 输出 值 are relative coordinates
* within the cropped region.
*
* @author CH
* @since 2026-08-08
 */
public class FacePluginLandmarkTranslator implements Translator<Image, float[]> {

    /** 输入尺寸 */
    /** 输入_大小 */
    private static final int INPUT_SIZE = 64;

    /** 创建 facepluginlandmarktranslator 实例 */
    public FacePluginLandmarkTranslator() {
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        if (input.getHeight() != INPUT_SIZE || input.getWidth() != INPUT_SIZE) {
            input = input.resize(INPUT_SIZE, INPUT_SIZE, false);
        }
        // 灰度 CHW + /256 归一化
        return OnnxImageProcessor.toModelInput(
                input, INPUT_SIZE, INPUT_SIZE,
                1, true, 0.0f, 1.0f / 256.0f,
                ctx.getNDManager());
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
 // 输入已包含 批量 维（shape [1, C, H, W]），无需 batchifier 再次叠加
        return null;
    }
}
