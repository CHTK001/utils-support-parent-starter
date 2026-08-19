package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * FacePlugin face landmark detection Translator (MobileFaceNet).
 *
 * <p>Model: MobileFaceNet
 * Input:  1x1x64x64 grayscale, normalized by /256
 * Output: 136-dim (68 landmarks * 2 coordinates), values in [0, 1] relative to cropped face
 *
 * <p>The caller must crop the face region from the image using the detected bounding box
 * before passing it to this translator. The 136 output values are relative coordinates
 * within the cropped region.
 *
 * @author CH
 * @since 2026-08-08
 */
public class FacePluginLandmarkTranslator implements Translator<Image, float[]> {

    /** 输入尺寸 */
    /** Input_size */
    private static final int INPUT_SIZE = 64;

    /** 创建 FacePluginLandmarkTranslator 实例 */
    public FacePluginLandmarkTranslator() {
    }

    @Override
    /** 处理Input */
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
    /** 处理Output */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        return array.toFloatArray();
    }

@Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        // 输入已包含 batch 维（shape [1, C, H, W]），无需 batchifier 再次叠加
        return null;
    }
}
