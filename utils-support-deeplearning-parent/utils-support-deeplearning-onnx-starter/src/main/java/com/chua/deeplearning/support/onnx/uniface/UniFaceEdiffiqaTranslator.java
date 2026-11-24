package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * UniFace eDifFIQA 人脸质量评分 Translator。
 *
 * <p>对应 UniFace(Python) {@code uniface/quality/ediffiqa.py}。输入为已裁剪的人脸区域，
 * 直接缩放到 {@value #INPUT_SIZE}×{@value #INPUT_SIZE} 并按
 * {@code (x - 127.5) / 127.5} 归一化（RGB；等价于 0~1 空间的
 * {@code (x - 0.5) / 0.5}）。Python 侧不做 5 点对齐，本实现保持一致。</p>
 *
 * <p>模型输出单个标量分数，直接作为质量分返回（越高质量越好）。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFaceEdiffiqaTranslator implements Translator<Image, Float> {

    /**
     * 模型输入边长。
     */
    private static final int INPUT_SIZE = 112;

    /**
     * 处理输入
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        return UniFaceImagePreprocessor.toTensor(input, INPUT_SIZE, INPUT_SIZE,
                UniFaceImagePreprocessor.HALF_MEAN, UniFaceImagePreprocessor.HALF_STD,
                ctx.getNDManager());
    }

    /**
     * 处理输出
     */
    @Override
    public Float processOutput(TranslatorContext ctx, NDList list) {
        NDArray out = list.getFirst();
        float[] values = out.toFloatArray();
        if (values.length < 1) {
            log.warn("[uniface-ediffiqa] 输出为空");
            return 0f;
        }
        return values[0];
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
