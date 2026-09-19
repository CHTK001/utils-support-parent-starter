package com.chua.deeplearning.support.onnx.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * nafnet ONNX             /            /
 *
 * <p>         :                                        384   64                HWC     CHW                       [0, 1]
 *
 * <p>         :           [0, 1]          [0, 255] uint8                                     Image
 *
 * <p>      : <a href="https://github.com/megvii-research/NAFNet">NAFNet</a>
 *
 * @author CH
 * @since 2026-05-09
 */
@Slf4j
public class NafNetTranslator implements Translator<Image, Image> {

    /**
     * nafnet                                                                 0
     */
    private static final int MIN_SIZE = 384;

    /**
     * nafnet
     */
    private static final int SIZE_ALIGN = 64;

    /**
     * nafnet ONNX nd管理器
     */
    private NDManager manager;

    /**
     *                                                      
     */
    private int origWidth;

    /**
     *                                                      
     */
    private int origHeight;

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) {
        this.manager = ctx.getNDManager();
        if (log.isDebugEnabled()) {
            log.debug("NAFNet ONNX                   ");
        }
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        this.origWidth = src.getWidth();
        this.origHeight = src.getHeight();

        int targetW = alignSize(origWidth);
        int targetH = alignSize(origHeight);
        BufferedImage resized = resizeIfNeeded(src, targetW, targetH);
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
        NDArray array = manager.create(pixels, new Shape(1, 3, h, w));

        if (log.isDebugEnabled()) {
            log.debug("NAFNet       : {}x{} -> {}x{}, shape={}", origWidth, origHeight, targetW, targetH, array.getShape());
        }
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray outputImg = list.singletonOrThrow();
        long[] shape = outputImg.getShape().getShape();

 // 兼容 [1, C, H, W] 与 [C, H, W]，不调用 squeeze/clip（ONNX ndarray 不支持）
        int off = shape.length == 4 ? 1 : 0;
        int outH = (int) shape[off + 1];
        int outW = (int) shape[off + 2];
        float[] data = outputImg.toFloatArray();

        // CHW [0, 1] -> HWC [0, 255] uint8，手动构建 BufferedImage
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        int stride = outH * outW;
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int idx = y * outW + x;
                int r = clampU8(data[idx]);
                int g = clampU8(data[idx + stride]);
                int b = clampU8(data[idx + 2 * stride]);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        // 输出尺寸与输入不一致时，缩放回原始尺寸
        if (outW != origWidth || outH != origHeight) {
            img = resizeIfNeeded(img, origWidth, origHeight);
        }

        if (log.isDebugEnabled()) {
            log.debug("NAFNet       : {}x{}", img.getWidth(), img.getHeight());
        }
        return ImageFactory.getInstance().fromImage(img);
    }

    /**
     * 将 [0, 1] 浮点像素钳制并转为 [0, 255] uint8。
     *
     * @param v 浮点像素值
     * @return 0-255 整数
     */
    private static int clampU8(float v) {
        float x = Math.max(0.0f, Math.min(1.0f, v));
        return (int) Math.round(x * 255.0f);
    }

    /**
     * 需要时缩放图像到目标尺寸（高质量双三次）。
     *
     * @param src   源图
     * @param w     目标宽
     * @param h     目标高
     * @return 缩放后的图（尺寸一致时返回原图）
     */
    private static BufferedImage resizeIfNeeded(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) {
            return src;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.drawImage(src, 0, 0, w, h, null);
        g2.dispose();
        return out;
    }

    /**
     * 最小_大小          大小_ALIGN
     *
     * @param size                       
     * @return                                
     */
    private static int alignSize(int size) {
        if (size < MIN_SIZE) {
            return MIN_SIZE;
        }
        return ((size + SIZE_ALIGN - 1) / SIZE_ALIGN) * SIZE_ALIGN;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
