package com.chua.deeplearning.support.onnx.animegan;

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
 * animeganv2 Face Portrait V2 Translator（NCHW 布局）。
 *
 * <p>Face Portrait V2 模型的 ONNX 导出为 NCHW 布局 {@code [1,3,512,512]}，
 * 与 Hayao/Shinkai/Paprika 的 NHWC {@code [1,512,512,3]} 不同，需独立处理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AnimeGanV2NchwTranslator implements Translator<Image, Image> {

    /**
     * 输入尺寸（固定 512x512）。
     */
    private static final int INPUT_SIZE = 512;

    /**
     * 原始图像宽。
     */
    private int originalWidth;

    /**
     * 原始图像高。
     */
    private int originalHeight;

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        // 提取 HWC 像素，AWT 缩放 512x512，归一化 [-1,1]，手动重排为 NCHW
        float[] hwc = resizeHwc(hwcPixels(input), originalWidth, originalHeight, INPUT_SIZE, INPUT_SIZE);
        int wh = INPUT_SIZE * INPUT_SIZE;
        float[] chw = new float[3 * wh];
        for (int i = 0; i < wh; i++) {
            chw[i] = hwc[i * 3] / 255f * 2f - 1f;
            chw[wh + i] = hwc[i * 3 + 1] / 255f * 2f - 1f;
            chw[2 * wh + i] = hwc[i * 3 + 2] / 255f * 2f - 1f;
        }
        NDArray array = ctx.getNDManager().create(chw, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv2Nchw NCHW 输入: {}", array.getShape());
        }
        return new NDList(array);
    }

    @Override
    /**
     * 处理输出
    */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();
        Shape outShape = output.getShape();
        int dim = outShape.dimension();
        int h = dim >= 3 ? (int) outShape.get(dim - 2) : (int) outShape.get(0); // [P3C 3.7 豁免] 张量形状维度下标
        int w = dim >= 3 ? (int) outShape.get(dim - 1) : (int) outShape.get(0); // [P3C 3.7 豁免] 张量形状维度下标
        float[] data = output.toFloatArray();
        int wh = w * h;
        int[] rgb = new int[wh];
        for (int i = 0; i < wh; i++) {
            float r = data[i] * 127.5f + 127.5f;
            float g = data[wh + i] * 127.5f + 127.5f;
            float b = data[2 * wh + i] * 127.5f + 127.5f;
            rgb[i] = (clip(r) << 16) | (clip(g) << 8) | clip(b);
        }
        java.awt.image.BufferedImage result = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, rgb, 0, w);
        if (w != originalWidth || h != originalHeight) {
            result = resizeBuffered(result, originalWidth, originalHeight);
        }
        return ImageFactory.getInstance().fromImage(result);
    }

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return Batchifier.fromString("none");
    }

    /**
     * Clip
     *
     * @param v v
     * @return clip的结果
     */
    private static int clip(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /**
     * hwcpixels
     *
     * @param input 输入
     * @return hwcPixels的结果
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
        throw new IllegalStateException("无法提取 BufferedImage 像素");
    }

    /**
     * 调整大小Hwc
     *
     * @param src src
     * @param sw sw
     * @param sh sh
     * @param dw dw
     * @param dh dh
     * @return resizeHwc的结果
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
     * 调整大小缓冲
     *
     * @param src src
     * @param dw dw
     * @param dh dh
     * @return resize缓冲的结果
     */
    private static java.awt.image.BufferedImage resizeBuffered(java.awt.image.BufferedImage src, int dw, int dh) {
        return ImageUtils.resize(src, dw, dh, org.opencv.imgproc.Imgproc.INTER_CUBIC);
    }
}
