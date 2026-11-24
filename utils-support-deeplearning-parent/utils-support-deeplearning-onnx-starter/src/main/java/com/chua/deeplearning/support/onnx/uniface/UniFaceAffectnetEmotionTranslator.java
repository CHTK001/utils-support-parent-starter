// [P3C A20 豁免] A20/A30/A31: 情绪标签为界面字符串字面量，豁免。
package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.ai.result.PredictResult;
import lombok.extern.slf4j.Slf4j;

/**
 * UniFace AffectNet7 表情识别 Translator。
 *
 * <p>对应 UniFace(Python) {@code uniface/attribute/emotion.py}（AFFECNET7 权重）。
 * 输入为已裁剪的人脸区域，直接缩放到 {@value #INPUT_SIZE}×{@value #INPUT_SIZE}，
 * RGB + ImageNet 归一化（Python 侧以 5 点关键点对齐后裁剪，预裁剪输入跳过对齐）。</p>
 *
 * <p>模型（TorchScript 导出）输出可能为 tuple，取其首个 7 维张量；softmax 后
 * 取 argmax 得到情绪标签与置信度。</p>
 *
 * <p>输出 {@link PredictResult}：{@code value} 为情绪标签、{@code confidence} 为
 * 最大概率、{@code floatValue} 为 7 维概率。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFaceAffectnetEmotionTranslator implements Translator<Image, PredictResult> {

    /**
     * 模型输入边长。
     */
    private static final int INPUT_SIZE = 112;

    /**
     * 情绪类别数（AffectNet7）。
     */
    private static final int NUM_EMOTIONS = 7;

    /**
     * 情绪标签（与模型输出顺序一致）。
     */
    private static final String[] EMOTION_LABELS = {
            "Neutral", "Happy", "Sad", "Surprise", "Fear", "Disgust", "Angry"};

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
    public PredictResult processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = null;
        for (NDArray out : list) {
            if ((int) out.getShape().size() == NUM_EMOTIONS) {
                logits = out;
                break;
            }
        }
        if (logits == null) {
            logits = list.getFirst();
        }

        float[] probs = softmax(logits.toFloatArray());
        if (probs.length < NUM_EMOTIONS) {
            log.warn("[uniface-affectnet] 输出长度不足: {}", probs.length);
            return PredictResult.builder().value("Unknown").confidence(0d).build();
        }

        int idx = 0;
        for (int i = 1; i < NUM_EMOTIONS; i++) {
            if (probs[i] > probs[idx]) {
                idx = i;
            }
        }

        return PredictResult.builder()
                .value(EMOTION_LABELS[idx])
                .confidence((double) probs[idx])
                .floatValue(probs)
                .build();
    }

    /**
     * 数值稳定 softmax。
     *
     * @param logits logits
     * @return 概率
     */
    private float[] softmax(float[] logits) {
        float max = logits[0];
        for (int i = 1; i < logits.length; i++) {
            max = Math.max(max, logits[i]);
        }
        float sum = 0f;
        float[] probs = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            probs[i] = (float) Math.exp(logits[i] - max);
            sum += probs[i];
        }
        for (int i = 0; i < probs.length; i++) {
            probs[i] /= sum;
        }
        return probs;
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
