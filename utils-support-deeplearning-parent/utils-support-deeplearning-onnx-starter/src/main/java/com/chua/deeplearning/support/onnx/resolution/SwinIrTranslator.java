package com.chua.deeplearning.support.onnx.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * swinir ONNX
 * <p>
 * denoising / 父-resolution
 * </p>
 * <p>
 *      :
 * <ul>
 *   <li>HWC     CHW                       [0, 1]          
 *   <li>          mean/std                    
 * </ul>
 * <p>
 *      : Heliosoph/swinir-onnx
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SwinIrTranslator implements Translator<Image, Image> {

    /** 均值数组 */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    /** 标准差数组 */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    /** 宽度 */
    private int width;
    /** 高度 */
    private int height;

    /**
     * swinir 模型固定输入尺寸（128x128）
     */
    private static final int INPUT_SIZE = 128;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();

        BufferedImage src = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = resize(src, INPUT_SIZE, INPUT_SIZE);
        int h = resized.getHeight();
        int w = resized.getWidth();

        // HWC -> CHW, [0, 255] -> [0, 1]，手动像素拷贝规避 ONNX NDArray 不支持的 transpose
        float[] pixels = new float[3 * h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = resized.getRGB(x, y);
                int idx = y * w + x;
                pixels[idx] = ((argb >> 16) & 0xFF) / 255.0f;
                pixels[h * w + idx] = ((argb >> 8) & 0xFF) / 255.0f;
                pixels[2 * h * w + idx] = (argb & 0xFF) / 255.0f;
            }
        }
        NDManager manager = ctx.getNDManager();
        NDArray array = manager.create(pixels, new Shape(1, 3, h, w));

        // mean/std 归一化（手动，规避 sub/div 不支持）
        float[] norm = array.toFloatArray();
        for (int i = 0; i < norm.length; i++) {
            norm[i] = (norm[i] - MEAN[0]) / STD[0];
        }
        NDArray array2 = manager.create(norm, new Shape(1, 3, h, w));

        return new NDList(array2);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray outputImg = list.singletonOrThrow();
        long[] shape = outputImg.getShape().getShape();

 // 兼容 [1, C, H, W] 与 [C, H, W]，不调用 squeeze（ONNX ndarray 会递归崩溃）
        int off = shape.length == 4 ? 1 : 0;
        int outH = (int) shape[off + 1];
        int outW = (int) shape[off + 2];
        float[] data = outputImg.toFloatArray();

        // CHW -> 还原 mean/std，转 [0, 255] uint8，手动构建 BufferedImage
        int stride = outH * outW;
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int idx = y * outW + x;
                float r = (data[idx] * STD[0] + MEAN[0]) * 255.0f;
                float g = (data[idx + stride] * STD[0] + MEAN[0]) * 255.0f;
                float b = (data[idx + 2 * stride] * STD[0] + MEAN[0]) * 255.0f;
                img.setRGB(x, y, (clampU8(r) << 16) | (clampU8(g) << 8) | clampU8(b));
            }
        }

        // 输出尺寸与输入不一致时，缩放回原始尺寸
        if (outW != width || outH != height) {
            img = resize(img, width, height);
        }

        return ImageFactory.getInstance().fromImage(img);
    }

    /**
     * 将浮点像素钳制并转为 [0, 255] uint8。
     *
     * @param v 浮点像素值
     * @return 0-255 整数
     */
    private static int clampU8(float v) {
        float x = Math.max(0.0f, Math.min(255.0f, v));
        return (int) Math.round(x);
    }

    /**
     * 缩放图像到目标尺寸（高质量双三次）。
     *
     * @param src 源图
     * @param w   目标宽
     * @param h   目标高
     * @return 缩放后的图
     */
    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.drawImage(src, 0, 0, w, h, null);
        g2.dispose();
        return out;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
