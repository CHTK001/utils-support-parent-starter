package com.chua.deeplearning.support.onnx.liveness;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;

/**
 * FLXC 炫彩人脸活体检测 Translator（模型scope iic/cv_manual_face-liveness_flxc）。
 *
 * <p>ResNet 结构，输入 112×112×12（4 帧 × RGB 3 通道，炫彩光源序列），输出
 * {@code [1,2]} = [活体概率, 假体概率]。返回索引 0（活体概率）作为活体分数。</p>
 *
 * <p>炫彩活体需多帧光反射序列；单图输入时以重复帧填充（效果受限，建议接入采流设备）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FlXcLivenessTranslator implements Translator<Image, Float> {

    /**
     * 输入尺寸。
     */
    private static final int INPUT_SIZE = 112;

    /**
     * 通道数（4 帧 × 3）。
     */
    private static final int CHANNELS = 12;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, org.opencv.imgproc.Imgproc.INTER_LINEAR);
        int[] pixels = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        float[] data = new float[CHANNELS * INPUT_SIZE * INPUT_SIZE];
        // 单帧 RGB 重复 4 帧构造 12 通道
        for (int frame = 0; frame < 4; frame++) {
            int base = frame * 3 * INPUT_SIZE * INPUT_SIZE;
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                data[base + i] = ((p >> 16) & 0xff) / 255f;
                data[base + INPUT_SIZE * INPUT_SIZE + i] = ((p >> 8) & 0xff) / 255f;
                data[base + 2 * INPUT_SIZE * INPUT_SIZE + i] = (p & 0xff) / 255f;
            }
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(1, CHANNELS, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Float processOutput(TranslatorContext ctx, NDList list) {
        NDArray out = list.getFirst();
        float[] values = out.toFloatArray();
        if (values.length < 1) {
            log.warn("[liveness] FLXC 输出为空");
            return 0f;
        }
        // [活体概率, 假体概率]，取活体概率
        return Math.max(0f, Math.min(1f, values[0]));
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
 // ONNX Runtime 的 ndarray 不支持 Stack，单图推理不批处理
        return null;
    }
}
