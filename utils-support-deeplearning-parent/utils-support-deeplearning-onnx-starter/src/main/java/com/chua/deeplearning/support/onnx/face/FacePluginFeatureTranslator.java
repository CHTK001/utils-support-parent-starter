package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
* faceplugin face 特征 extraction Translator (Inception-Rnet-v50).
*
* <p>Model: irn50_pytorch
* 输入:  1x3x128x128 RGB, normalized by /256
* 输出: 256-dim 嵌入 (irn50.远期 内部已做 torch.最大 合并两段 256 维),
* 在输出阶段只需 L2 归一化。
*
* <p>The face image must be aligned (using landmarks) before passing to this translator.
* Alignment 参数: lefteyex=48, lefteyey=64, righteyex=40 (从 Python SDK).
*
* @author CH
* @since 2026-08-08
 */
public class FacePluginFeatureTranslator implements Translator<Image, float[]> {

    /** 输入尺寸 */
    /** 输入_大小 */
    private static final int INPUT_SIZE = 128;

    /** 创建 faceplugin特征translator 实例 */
    public FacePluginFeatureTranslator() {
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        if (input.getHeight() != INPUT_SIZE || input.getWidth() != INPUT_SIZE) {
            input = input.resize(INPUT_SIZE, INPUT_SIZE, false);
        }
        // RGB CHW + /256 归一化
        return OnnxImageProcessor.toModelInput(
                input, INPUT_SIZE, INPUT_SIZE,
                3, false, 0.0f, 1.0f / 256.0f,
                ctx.getNDManager());
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        float[] raw = array.toFloatArray();

        // irn50.forward 内部已做 torch.max(前半, 后半)，模型直接输出 256 维
        // 这里只需 L2 归一化
        float norm = 0.0f;
        for (float v : raw) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0.0f) {
            for (int i = 0; i < raw.length; i++) {
                raw[i] /= norm;
            }
        }

        return raw;
    }

@Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
 // 输入已包含 批量 维（shape [1, C, H, W]），无需 batchifier 再次叠加
        return null;
    }
}
