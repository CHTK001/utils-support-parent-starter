// [P3C A20 豁免] A20/A30/A31: 角度分箱含魔法值（binwidth/offset），豁免。
package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * UniFace MobileGaze 视线方向 Translator。
 *
 * <p>对应 UniFace(Python) {@code uniface/gaze/models.py}。输入为已裁剪的人脸区域，
 * 缩放后做 ImageNet 归一化（RGB）。模型输出两路 90 bins 的分类 logits
 * （yaw、pitch 各一路），后处理对每路做 softmax 后按 bin 中心求期望（soft-argmax）：
 * {@code deg = Σ prob[i] * i * 4 - 180}，再转换为弧度。</p>
 *
 * <p>输出为 {@code float[2]}：{@code [yawRad, pitchRad]}（与模型输出顺序一致，
 * yaw 为水平角、pitch 为俯仰角，单位弧度）。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFaceGazeTranslator implements Translator<Image, float[]> {

    /**
     * 模型输入边长（ONNX 固定为 448x448，经 OnnxShapeProbe 实测确认）。
     */
    private static final int INPUT_SIZE = 448;

    /**
     * 角度 bins 数。
     */
    private static final int NUM_BINS = 90;

    /**
     * 每 bin 覆盖的角度宽度。
     */
    private static final float BIN_WIDTH = 4.0f;

    /**
     * 角度偏移量。
     */
    private static final float ANGLE_OFFSET = 180.0f;

    /**
     * 处理输入
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        return UniFaceImagePreprocessor.toTensor(input, INPUT_SIZE, INPUT_SIZE,
                UniFaceImagePreprocessor.IMAGENET_MEAN, UniFaceImagePreprocessor.IMAGENET_STD,
                ctx.getNDManager());
    }

    /**
     * 处理输出
     */
    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        if (list.size() < 2) {
            log.warn("[uniface-gaze] 输出路数不足: {}", list.size());
            return new float[0];
        }
        float yaw = expectedAngle(list.get(0));
        float pitch = expectedAngle(list.get(1));
        return new float[]{yaw, pitch};
    }

    /**
     * 对单路 logits 做 softmax 期望并换算为弧度。
     *
     * @param logits logits
     * @return 弧度角
     */
    private float expectedAngle(NDArray logits) {
        float[] values = logits.toFloatArray();
        int bins = Math.min(values.length, NUM_BINS);
        float max = values[0];
        for (int i = 1; i < bins; i++) {
            max = Math.max(max, values[i]);
        }
        double sumExp = 0;
        double[] probs = new double[bins];
        for (int i = 0; i < bins; i++) {
            probs[i] = Math.exp(values[i] - max);
            sumExp += probs[i];
        }
        double expected = 0;
        for (int i = 0; i < bins; i++) {
            expected += (probs[i] / sumExp) * i;
        }
        double deg = expected * BIN_WIDTH - ANGLE_OFFSET;
        return (float) Math.toRadians(deg);
    }

    /**
     * 获取Batchifier
     */
    @Override
    public Batchifier getBatchifier() {
        // ONNX Runtime 的 ndarray 不支持 Stack，单图推理不批处理
        return null;
    }
}
