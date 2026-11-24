// [P3C A20 豁免] A20/A30/A31: 欧拉角分解含魔法值（奇异阈值），豁免。
package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * UniFace 6D 头部姿态 Translator（旋转矩阵 → 欧拉角）。
 *
 * <p>对应 UniFace(Python) {@code uniface/headpose/models.py}。输入为已裁剪的人脸区域，
 * 缩放后做 ImageNet 归一化（RGB）。模型输出 (1,3,3) 旋转矩阵，
 * 按 ZYX 约定分解为 [pitch, yaw, roll]（度）：
 * {@code sy = sqrt(R00² + R10²)}；非奇异时
 * {@code pitch = atan2(R21, R22)}、{@code yaw = atan2(-R20, sy)}、
 * {@code roll = atan2(R10, R00)}；奇异（sy &lt; 1e-6）时
 * {@code pitch = atan2(-R12, R11)}、{@code roll = 0}。</p>
 *
 * <p>输出为 {@code float[3]}：{@code [pitchDeg, yawDeg, rollDeg]}。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFaceHeadPoseTranslator implements Translator<Image, float[]> {

    /**
     * 模型输入边长。
     */
    private static final int INPUT_SIZE = 224;

    /**
     * 奇异判定阈值。
     */
    private static final double SINGULAR_EPS = 1e-6;

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
        NDArray matrix = list.getFirst();
        float[] r = matrix.toFloatArray();
        if (r.length < 9) {
            log.warn("[uniface-headpose] 输出长度不足: {}", r.length);
            return new float[0];
        }
        double r00 = r[0];
        double r10 = r[3];
        double r11 = r[4];
        double r12 = r[5];
        double r20 = r[6];
        double r21 = r[7];
        double r22 = r[8];

        double sy = Math.sqrt(r00 * r00 + r10 * r10);
        boolean singular = sy < SINGULAR_EPS;

        double pitch = singular ? Math.atan2(-r12, r11) : Math.atan2(r21, r22);
        double yaw = Math.atan2(-r20, sy);
        double roll = singular ? 0.0 : Math.atan2(r10, r00);

        return new float[]{
                (float) Math.toDegrees(pitch),
                (float) Math.toDegrees(yaw),
                (float) Math.toDegrees(roll)
        };
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
