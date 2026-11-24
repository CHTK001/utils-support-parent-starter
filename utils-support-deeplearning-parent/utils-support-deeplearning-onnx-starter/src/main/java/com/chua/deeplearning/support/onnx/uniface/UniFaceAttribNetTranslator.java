// [P3C A20 豁免] A20/A30/A31: 属性概率直接透传含魔法值，豁免。
package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * UniFace FaceAttribNet 面部状态属性 Translator。
 *
 * <p>对应 UniFace(Python) {@code uniface/attribute/faceattribnet.py}。输入为已裁剪的人脸区域，
 * letterbox 到 {@value #INPUT_SIZE}×{@value #INPUT_SIZE}，仅缩放到 [0,1]
 * （均值/方差归一化已并入图内）。模型输出 (1,5) 五个独立属性的概率
 * （图内已含 sigmoid），直接透传：
 * {@code [左眼睁开, 右眼睁开, 戴眼镜, 戴口罩, 戴墨镜]}。</p>
 *
 * <p>输出为 {@code float[5]}，各元素为 0~1 的概率。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFaceAttribNetTranslator implements Translator<Image, float[]> {

    /**
     * 模型输入边长。
     */
    private static final int INPUT_SIZE = 128;

    /**
     * 属性数量。
     */
    private static final int NUM_ATTRIBUTES = 5;

    /**
     * 处理输入
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        return UniFaceImagePreprocessor.toLetterboxTensor(input, INPUT_SIZE,
                UniFaceImagePreprocessor.ZERO_MEAN, UniFaceImagePreprocessor.ONE_STD,
                ctx.getNDManager());
    }

    /**
     * 处理输出
     */
    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray out = list.getFirst();
        float[] values = out.toFloatArray();
        if (values.length < NUM_ATTRIBUTES) {
            log.warn("[uniface-attribnet] 输出长度不足: {}", values.length);
            return new float[0];
        }
        float[] result = new float[NUM_ATTRIBUTES];
        System.arraycopy(values, 0, result, 0, NUM_ATTRIBUTES);
        return result;
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
