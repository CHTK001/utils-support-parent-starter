package com.chua.deeplearning.support.onnx.style;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Fast Neural Style Transfer Translator
 * <p>
 * 基于 Johnson 等人的感知损失实时风格迁移论文（Perceptual Losses for Real-Time Style Transfer），
 * 使用 Instance Normalization 实现快速风格迁移。
 * <p>
 * 支持的风格模型（来自 ONNX 官方模型仓库）：
 * <ul>
 *   <li>candy - 糖果风格</li>
 *   <li>mosaic - 马赛克风格</li>
 *   <li>rain-princess - 雨中公主风格</li>
 *   <li>udnie - 抽象风格</li>
 *   <li>pointilism - 点彩派/卜利吉风格</li>
 * </ul>
 * <p>
 * 模型输入: NCHW [1, 3, H, W] float32，RGB [0, 255]（无归一化）
 * 模型输出: NCHW [1, 3, H, W] float32，RGB [0, 255]（需 clip）
 * <p>
 * 参考: https://github.com/onnx/models/tree/main/validated/vision/style_transfer/fast_neural_style
 *
 * @author CH
 * @version 4.0.0.42
 * @since 2026/8/15
 */
@Slf4j
public class FastNeuralStyleTranslator implements Translator<Image, Image> {

    /**
     * 输入尺寸（ONNX 官方 fast_neural_style 模型固定 224x224）
     */
    private static final int INPUT_SIZE = 224;

    /**
     * 原始图像宽度
     */
    private int originalWidth;

    /**
     * 原始图像高度
     */
    private int originalHeight;

    /**
     * 最大输入尺寸限制（防止内存溢出）
     */
    private int maxSize = 224;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("FastNeuralStyle 输入图像尺寸: {}x{}", originalWidth, originalHeight);
        }

        // 提取 HWC 像素数组（RGB [0,255]），规避 onnxruntime NDArray 不支持的 transpose 操作
        float[] hwc = hwcPixels(input);
        int height = originalHeight;
        int width = originalWidth;

        // ONNX 官方 fast_neural_style 模型固定输入 224x224
        hwc = resizeHwc(hwc, width, height, INPUT_SIZE, INPUT_SIZE);
        width = INPUT_SIZE;
        height = INPUT_SIZE;
        if (log.isDebugEnabled()) {
            log.debug("缩放图像: {}x{} -> {}x{}", originalWidth, originalHeight, width, height);
        }

        // HWC -> CHW（手动重排，规避 transpose）
        float[] chw = new float[3 * height * width];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < height * width; i++) {
                chw[c * height * width + i] = hwc[i * 3 + c];
            }
        }

        NDArray array = ctx.getNDManager().create(chw, new ai.djl.ndarray.types.Shape(1, 3, height, width));
        if (log.isDebugEnabled()) {
            log.debug("NCHW 数组: {}", array.getShape());
        }
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();

        if (log.isDebugEnabled()) {
            log.debug("FastNeuralStyle 模型输出: {}", output.getShape());
        }

        // 输出 NCHW float，手动重排为 HWC 并 clip，规避 onnxruntime 不支持的 transpose/clip/fromNDArray
        Shape outShape = output.getShape();
        int dim = outShape.dimension();
        int h = dim >= 4 ? (int) outShape.get(2) : (dim == 3 ? (int) outShape.get(1) : (int) outShape.get(0));
        int w = dim >= 3 ? (int) outShape.get(dim - 1) : (int) outShape.get(0);
        int total = 3 * h * w;
        float[] data = output.toFloatArray();
        if (data.length < total) {
            total = data.length;
        }

        // 取 batch 0 的 CHW → HWC，clip 0~255
        int wh = w * h;
        int[] rgb = new int[wh];
        for (int i = 0; i < wh; i++) {
            float r = data[i];
            float g = data[wh + i];
            float b = data[2 * wh + i];
            rgb[i] = (clip(r) << 16) | (clip(g) << 8) | clip(b);
        }

        java.awt.image.BufferedImage result = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, rgb, 0, w);

        // 输出固定 224x224，恢复原始尺寸
        if (w != originalWidth || h != originalHeight) {
            result = resizeBuffered(result, originalWidth, originalHeight);
        }
        return ImageFactory.getInstance().fromImage(result);
    }

    /**
     * clip 到 [0,255]。
     *
     * @param v 值
     * @return 0~255 整数
     */
    private static int clip(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /**
     * 提取 HWC 像素数组（RGB [0,255]）。
     *
     * @param input DJL 图像
     * @return HWC float 数组
     */
    private static float[] hwcPixels(Image input) {
        Object wrapped = input.getWrappedImage();
        if (wrapped instanceof java.awt.image.BufferedImage bi) {
            int w = bi.getWidth();
            int h = bi.getHeight();
            float[] out = new float[w * h * 3];
            int[] rgb = bi.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < rgb.length; i++) {
                out[i * 3] = (rgb[i] >> 16) & 0xFF;
                out[i * 3 + 1] = (rgb[i] >> 8) & 0xFF;
                out[i * 3 + 2] = rgb[i] & 0xFF;
            }
            return out;
        }
        // 兜底：走 DJL NDArray（可能不支持，仅作回退）
        throw new IllegalStateException("无法提取 BufferedImage 像素");
    }

    /**
     * HWC 双线性缩放。
     *
     * @param src  源 HWC 像素
     * @param sw   源宽
     * @param sh   源高
     * @param dw   目标宽
     * @param dh   目标高
     * @return 目标 HWC 像素
     */
    private static float[] resizeHwc(float[] src, int sw, int sh, int dw, int dh) {
        float[] out = new float[dw * dh * 3];
        float xs = (float) sw / dw;
        float ys = (float) sh / dh;
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, (int) (y * ys));
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(sw - 1, (int) (x * xs));
                int si = (sy * sw + sx) * 3;
                int di = (y * dw + x) * 3;
                out[di] = src[si];
                out[di + 1] = src[si + 1];
                out[di + 2] = src[si + 2];
            }
        }
        return out;
    }

    /**
     * BufferedImage 缩放。
     *
     * @param src 源图
     * @param dw  目标宽
     * @param dh  目标高
     * @return 目标图
     */
    private static java.awt.image.BufferedImage resizeBuffered(java.awt.image.BufferedImage src, int dw, int dh) {
        return ImageUtils.resize(src, dw, dh, org.opencv.imgproc.Imgproc.INTER_CUBIC);
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.fromString("none");
    }

    /**
     * 设置最大输入尺寸
     *
     * @param maxSize 最大尺寸（像素）
     * @return 当前实例
     */
    public FastNeuralStyleTranslator setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        return this;
    }

    /**
     * 获取最大输入尺寸
     *
     * @return 最大尺寸
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * 获取原始图像尺寸
     *
     * @return [width, height]
     */
    public int[] getOriginalSize() {
        return new int[]{originalWidth, originalHeight};
    }
}