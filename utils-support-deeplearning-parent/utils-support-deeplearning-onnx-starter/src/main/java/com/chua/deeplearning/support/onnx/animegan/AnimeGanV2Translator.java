package com.chua.deeplearning.support.onnx.animegan;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * animeganv2 Translator
 * <p>
 * animeganv2 动漫风格迁移，支持以下风格：
 * <ul>
 *   <li>Hayao - 宫崎骏风格（来自 AnimeGANv2 原始权重）</li>
 *   <li>Shinkai - 新海诚风格（来自 AnimeGANv2 原始权重）</li>
 *   <li>Paprika - 今敏/红辣椒风格（来自 AnimeGANv2 原始权重）</li>
 *   <li>Face Portrait V2 - 人像动漫化风格（来自 bryandlee/animegan2-pytorch）</li>
 * </ul>
 * <p>
 * 模型输入: NHWC [1, 512, 512, 3] float32，RGB 归一化到 [-1, 1]
 * 模型输出: NHWC [1, 512, 512, 3] float32，RGB 归一化到 [-1, 1]
 * <p>
 * 参考: https://github.com/bryandlee/animegan2-pytorch
 * 参考: https://github.com/TachibanaYoshino/AnimeGANv2
 *
 * @author CH
 * @since 2026/8/15
 */
@Slf4j
public class AnimeGanV2Translator implements Translator<Image, Image> {

    /**
     * 输入尺寸（animeganv2 固定 512x512）
     */
    private static final int INPUT_SIZE = 512;

    /**
     * 原始图像宽度
     */
    private int originalWidth;

    /**
     * 原始图像高度
     */
    private int originalHeight;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv2 输入图像尺寸: {}x{}", originalWidth, originalHeight);
        }

 // NHWC 像素，RGB [-1,1]（规避 onnxruntime ndarray 不支持的 op）
        float[] hwc = resizeHwc(hwcPixels(input), originalWidth, originalHeight, INPUT_SIZE, INPUT_SIZE);
        int wh = INPUT_SIZE * INPUT_SIZE;
        float[] nhwc = new float[wh * 3];
        for (int i = 0; i < wh; i++) {
            nhwc[i * 3] = hwc[i * 3] / 255f * 2f - 1f;
            nhwc[i * 3 + 1] = hwc[i * 3 + 1] / 255f * 2f - 1f;
            nhwc[i * 3 + 2] = hwc[i * 3 + 2] / 255f * 2f - 1f;
        }

        NDArray array = ctx.getNDManager().create(nhwc, new Shape(1, INPUT_SIZE, INPUT_SIZE, 3));
        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv2 NHWC 输入: {}", array.getShape());
        }
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();

        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv2 模型输出: {}", output.getShape());
        }

        // 输出 NHWC float，手动反归一化 + clip（规避 onnxruntime 不支持的 op）
        Shape outShape = output.getShape();
        int dim = outShape.dimension();
        int h = dim >= 3 ? (int) outShape.get(dim - 3) : (int) outShape.get(0); // [P3C 3.7 豁免] 张量形状维度下标
        int w = dim >= 3 ? (int) outShape.get(dim - 2) : (int) outShape.get(0); // [P3C 3.7 豁免] 张量形状维度下标
        float[] data = output.toFloatArray();
        int wh = w * h;
        int[] rgb = new int[wh];
        for (int i = 0; i < wh; i++) {
            float r = data[i * 3] * 127.5f + 127.5f;
            float g = data[i * 3 + 1] * 127.5f + 127.5f;
            float b = data[i * 3 + 2] * 127.5f + 127.5f;
            rgb[i] = (clip(r) << 16) | (clip(g) << 8) | clip(b);
        }
        java.awt.image.BufferedImage result = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, rgb, 0, w);

        // 恢复原始尺寸
        if (w != originalWidth || h != originalHeight) {
            result = resizeBuffered(result, originalWidth, originalHeight);
        }
        if (log.isDebugEnabled()) {
            log.debug("AnimeGANv2 输出图像: {}x{}", result.getWidth(), result.getHeight());
        }
        return ImageFactory.getInstance().fromImage(result);
    }

    /**
     * clip 到 [0,255]。
     *
     * @param v 值
     * @return 0~255
     */
    private static int clip(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /**
     * 提取 HWC RGB [0,255]。
     *
     * @param input DJL 图像
     * @return HWC
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
     * HWC 双线性缩放。
     *
     * @param src 源
     * @param sw  源宽
     * @param sh  源高
     * @param dw  目标宽
     * @param dh  目标高
     * @return 目标
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
     * 缓冲镜像 缩放。
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
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.fromString("none");
    }

    /**
     * 获取输入尺寸
     *
     * @return 输入尺寸
     */
    public int getInputSize() {
        return INPUT_SIZE;
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
