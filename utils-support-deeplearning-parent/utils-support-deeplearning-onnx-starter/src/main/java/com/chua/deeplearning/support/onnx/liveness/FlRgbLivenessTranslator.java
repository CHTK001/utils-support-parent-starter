package com.chua.deeplearning.support.onnx.liveness;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;

/**
 * FLRGB 人脸活体检测 Translator（ModelScope iic/cv_manual_face-liveness_flrgb）。
 *
 * <p>ResNet 结构，输入 112×112 RGB（[0,1] 归一化），输出 {@code final_actions [1,2]}
 * = [活体概率, 假体概率]。返回索引 0（活体概率）作为活体分数（0~1，越高越可能是活体）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FlRgbLivenessTranslator implements Translator<Image, Float> {

    /**
     * 输入尺寸。
     */
    private static final int INPUT_SIZE = 112;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        int w = input.getWidth();
        int h = input.getHeight();
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        resized.getGraphics().drawImage(src, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
        int[] pixels = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        float[] data = new float[3 * INPUT_SIZE * INPUT_SIZE];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            data[i] = ((p >> 16) & 0xff) / 255f;
            data[i + INPUT_SIZE * INPUT_SIZE] = ((p >> 8) & 0xff) / 255f;
            data[i + 2 * INPUT_SIZE * INPUT_SIZE] = (p & 0xff) / 255f;
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public Float processOutput(TranslatorContext ctx, NDList list) {
        NDArray out = list.get(0);
        float[] values = out.toFloatArray();
        if (values.length < 1) {
            log.warn("[liveness] FLRGB 输出为空");
            return 0f;
        }
        // final_actions = [活体概率, 假体概率]，取活体概率
        float live = values[0];
        return Math.max(0f, Math.min(1f, live));
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
