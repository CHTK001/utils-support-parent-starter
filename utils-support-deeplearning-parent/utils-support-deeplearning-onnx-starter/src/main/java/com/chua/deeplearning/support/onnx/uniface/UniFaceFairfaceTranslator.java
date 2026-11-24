// [P3C A20 豁免] A20/A30/A31: 人口学标签与概率拼接含大量字符串字面量，豁免。
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
 * UniFace FairFace 人口学属性 Translator（种族 / 性别 / 年龄段）。
 *
 * <p>对应 UniFace(Python) {@code uniface/attribute/fairface.py}。输入为已裁剪的人脸区域；
 * Python 侧对 bbox 做 +25% 边距扩展后裁剪，预裁剪整图输入时退化为直接缩放到
 * {@value #INPUT_SIZE}×{@value #INPUT_SIZE}，RGB + ImageNet 归一化。</p>
 *
 * <p>模型输出三路 logits：race(7)、gender(2)、age(9)，各自 softmax 后取 argmax。
 * 性别索引按 {@code gender = 1 - raw} 归一（模型 0=Male → 标准 0=Female,1=Male）。</p>
 *
 * <p>输出 {@link PredictResult}：{@code value} 为 {@code "种族|性别|年龄段"} 组合串；
 * {@code confidence} 为三路最大概率的最小值；{@code floatValue} 为 18 维拼接概率
 * {@code [race7..., gender2..., age9...]}。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFaceFairfaceTranslator implements Translator<Image, PredictResult> {

    /**
     * 模型输入边长。
     */
    private static final int INPUT_SIZE = 224;

    /**
     * 种族类别数。
     */
    private static final int RACE_CLASSES = 7;

    /**
     * 性别类别数。
     */
    private static final int GENDER_CLASSES = 2;

    /**
     * 年龄段类别数。
     */
    private static final int AGE_CLASSES = 9;

    /**
     * 种族标签（与模型输出顺序一致）。
     */
    private static final String[] RACE_LABELS = {
            "White", "Black", "Latino Hispanic", "East Asian",
            "Southeast Asian", "Indian", "Middle Eastern"};

    /**
     * 性别标签（归一化后 0=Female, 1=Male）。
     */
    private static final String[] GENDER_LABELS = {"Female", "Male"};

    /**
     * 年龄段标签（与模型输出顺序一致）。
     */
    private static final String[] AGE_LABELS = {
            "0-2", "3-9", "10-19", "20-29", "30-39", "40-49", "50-59", "60-69", "70+"};

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
        if (list.size() < 3) {
            log.warn("[uniface-fairface] 输出路数不足: {}", list.size());
            return PredictResult.builder().value("Unknown").confidence(0d).build();
        }

        float[] raceProbs = softmaxByLength(list, RACE_CLASSES);
        float[] genderProbs = softmaxByLength(list, GENDER_CLASSES);
        float[] ageProbs = softmaxByLength(list, AGE_CLASSES);
        if (raceProbs == null || genderProbs == null || ageProbs == null) {
            log.warn("[uniface-fairface] 输出维度与 7/2/9 不匹配");
            return PredictResult.builder().value("Unknown").confidence(0d).build();
        }

        int raceIdx = argmax(raceProbs);
        int rawGenderIdx = argmax(genderProbs);
        int ageIdx = argmax(ageProbs);

        // 模型输出 0=Male, 1=Female → 归一为 0=Female, 1=Male
        int genderIdx = 1 - rawGenderIdx;

        String value = RACE_LABELS[raceIdx] + "|" + GENDER_LABELS[genderIdx] + "|" + AGE_LABELS[ageIdx];
        float confidence = Math.min(raceProbs[raceIdx],
                Math.min(genderProbs[rawGenderIdx], ageProbs[ageIdx]));

        float[] all = new float[RACE_CLASSES + GENDER_CLASSES + AGE_CLASSES];
        System.arraycopy(raceProbs, 0, all, 0, RACE_CLASSES);
        System.arraycopy(genderProbs, 0, all, RACE_CLASSES, GENDER_CLASSES);
        System.arraycopy(ageProbs, 0, all, RACE_CLASSES + GENDER_CLASSES, AGE_CLASSES);

        return PredictResult.builder()
                .value(value)
                .confidence((double) confidence)
                .floatValue(all)
                .build();
    }

    /**
     * 按目标长度从输出列表中匹配数组并做 softmax。
     *
     * @param list   输出列表
     * @param length 目标类别数
     * @return softmax 概率；未匹配返回 null
     */
    private float[] softmaxByLength(NDList list, int length) {
        for (NDArray out : list) {
            if ((int) out.getShape().size() == length) {
                return softmax(out.toFloatArray());
            }
        }
        return null;
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
     * argmax。
     *
     * @param values 概率
     * @return 最大值下标
     */
    private int argmax(float[] values) {
        int idx = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[idx]) {
                idx = i;
            }
        }
        return idx;
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
