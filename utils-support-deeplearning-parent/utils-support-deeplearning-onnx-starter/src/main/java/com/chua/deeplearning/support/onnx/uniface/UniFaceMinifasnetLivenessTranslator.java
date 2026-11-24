// [P3C A20 豁免] A20/A30/A31: 像素级通道重排含魔法值，豁免。
package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;

/**
 * UniFace MiniFASNet 人脸活体检测 Translator。
 *
 * <p>对应 UniFace(Python) {@code uniface/spoofing/minifasnet.py}。输入为已裁剪的人脸区域；
 * Python 侧以整图为 bbox 时 scale=min(1,1,model_scale)=1，裁剪退化为整图缩放，
 * 故本实现直接缩放到 {@value #INPUT_SIZE}×{@value #INPUT_SIZE}。模型输入为
 * <b>BGR 通道序的原始 float32 像素，不做任何归一化</b>（与 Python 侧一致）。</p>
 *
 * <p>模型输出 2 维 logits，softmax 后取 index=1（真实人脸）的概率作为活体分数。</p>
 *
 * <p>输出为 {@code Float}：P(real) ∈ [0,1]，越接近 1 越可能是真实人脸。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFaceMinifasnetLivenessTranslator implements Translator<Image, Float> {

    /**
     * 模型输入边长。
     */
    private static final int INPUT_SIZE = 80;

    /**
     * logits 维度。
     */
    private static final int NUM_LOGITS = 2;

    /**
     * 处理输入
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage src = UniFaceImagePreprocessor.unwrap(input);
        BufferedImage resized = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, Imgproc.INTER_LINEAR);
        int[] pixels = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        int total = INPUT_SIZE * INPUT_SIZE;
        float[] chw = new float[3 * total];
        // 模型要求 BGR 通道序、原始像素值（无归一化）
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            chw[i] = p & 0xff;
            chw[i + total] = (p >> 8) & 0xff;
            chw[i + 2 * total] = (p >> 16) & 0xff;
        }
        NDArray array = ctx.getNDManager().create(chw, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    /**
     * 处理输出
     */
    @Override
    public Float processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = list.getFirst();
        float[] values = logits.toFloatArray();
        if (values.length < NUM_LOGITS) {
            log.warn("[uniface-minifasnet] 输出长度不足: {}", values.length);
            return 0f;
        }
        float max = Math.max(values[0], values[1]);
        double expFake = Math.exp(values[0] - max);
        double expReal = Math.exp(values[1] - max);
        // P(real) = softmax[1]
        return (float) (expReal / (expFake + expReal));
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
