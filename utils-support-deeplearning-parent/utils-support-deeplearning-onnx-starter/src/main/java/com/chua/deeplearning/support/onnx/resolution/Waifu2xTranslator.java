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

import java.awt.image.BufferedImage;

/**
 * waifu2x ONNX                   /                        
 *
 * <p>         : HWC     CHW                       [0, 1]
 *
 * <p>         :           [0, 1]                   [0, 255] uint8             Image
 *
 * <p>      : <a href="https://github.com/nagadomi/waifu2x">waifu2x</a>
 *
 * @author CH
 * @since 2026-05-09
 */
@Slf4j
public class Waifu2xTranslator implements Translator<Image, Image> {

    /**
     * waifu2x ONNX NDManager                                                      
     */
    private NDManager manager;

    /**
     * 最近一次输入的 padding（输出侧裁剪用）
     */
    private int lastPadH;
    private int lastPadW;

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) {
        this.manager = ctx.getNDManager();
        if (log.isDebugEnabled()) {
            log.debug("waifu2x ONNX                   ");
        }
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage buffered = (BufferedImage) input.getWrappedImage();
        int h = buffered.getHeight();
        int w = buffered.getWidth();

        // swin_unet 内部存在下采样，输入尺寸必须为 8 的倍数，否则 Add 广播报错；不足处右侧/底部用 0 填充
        int padH = (8 - h % 8) % 8;
        int padW = (8 - w % 8) % 8;
        int ph = h + padH;
        int pw = w + padW;
        this.lastPadH = padH;
        this.lastPadW = padW;

        // HWC -> CHW, [0, 255] -> [0, 1]，手动像素拷贝规避 ONNX NDArray 不支持的 transpose
        float[] pixels = new float[3 * ph * pw];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = buffered.getRGB(x, y);
                int idx = y * pw + x;
                pixels[idx] = ((argb >> 16) & 0xFF) / 255.0f;
                pixels[ph * pw + idx] = ((argb >> 8) & 0xFF) / 255.0f;
                pixels[2 * ph * pw + idx] = (argb & 0xFF) / 255.0f;
            }
        }
        NDArray array = manager.create(pixels, new Shape(1, 3, ph, pw));

        if (log.isDebugEnabled()) {
            log.debug("waifu2x                   : shape={} (pad h={} w={})", array.getShape(), padH, padW);
        }
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray outputImg = list.singletonOrThrow();
        long[] shape = outputImg.getShape().getShape();

        // 兼容 [1, C, H, W] 与 [C, H, W]，不调用 squeeze（ONNX NDArray 会递归崩溃）
        int off = shape.length == 4 ? 1 : 0;
        int oh = (int) shape[off + 1];   // 输出全高 = 4 * (h + padH)
        int ow = (int) shape[off + 2];   // 输出全宽 = 4 * (w + padW)
        float[] data = outputImg.toFloatArray();

        // 去除输入侧 padding：输出实际有效区域为 4*h x 4*w
        int h = oh / 4 - lastPadH;
        int w = ow / 4 - lastPadW;
        int stride = oh * ow;

        // CHW [0, 1] -> HWC [0, 255] uint8，手动构建 BufferedImage
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = (y * 4 + lastPadH * 4) * ow + (x * 4 + lastPadW * 4);
                int r = clampU8(data[idx]);
                int g = clampU8(data[idx + stride]);
                int b = clampU8(data[idx + 2 * stride]);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("waifu2x                   : {}x{}", w, h);
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

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
