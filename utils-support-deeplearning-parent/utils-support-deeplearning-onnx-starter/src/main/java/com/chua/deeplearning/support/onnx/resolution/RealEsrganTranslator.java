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
 * Real-ESRGAN ONNX                      
 *
 * <p>       RRDBNet                                     
 *
 * <p>         :
 * <ul>
 *   <li>HWC     CHW                       [0, 1]          255.0   
 *   <li>          mean/std             RRDBNet              [0, 1]          
 * </ul>
 *
 * <p>         :
 * <ul>
 *   <li>          [0, 1]          [0, 255] uint8
 *   <li>          Image
 * </ul>
 *
 * <p>      : <a href="https://github.com/xinntao/Real-ESRGAN">Real-ESRGAN</a>
 *
 * @author CH
 * @since 2026-05-02
 */
@Slf4j
public class RealEsrganTranslator implements Translator<Image, Image> {

    /**
     *                                                             
     */
    private final int scale;

    /**
     * ndarray
     */
    private NDManager manager;

    /**
     *                              
     */
    public RealEsrganTranslator() {
        this(4);
    }

    /**
     *                              
     *
     * @param scale                   2     4   
     */
    public RealEsrganTranslator(int scale) {
        this.scale = scale;
    }

    @Override
    /**
     * Prepare
    */
    public void prepare(TranslatorContext ctx) {
        this.manager = ctx.getNDManager();
        if (log.isDebugEnabled()) {
            log.debug("Real-ESRGAN ONNX                   : scale={}x", scale);
        }
    }

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage buffered = (BufferedImage) input.getWrappedImage();
        int h = buffered.getHeight();
        int w = buffered.getWidth();

        // HWC -> CHW, [0, 255] -> [0, 1]，手动像素拷贝规避 ONNX NDArray 不支持的 transpose
        float[] pixels = new float[3 * h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = buffered.getRGB(x, y);
                int idx = y * w + x;
                pixels[idx] = ((argb >> 16) & 0xFF) / 255.0f;
                pixels[h * w + idx] = ((argb >> 8) & 0xFF) / 255.0f;
                pixels[2 * h * w + idx] = (argb & 0xFF) / 255.0f;
            }
        }
        NDArray array = manager.create(pixels, new Shape(1, 3, h, w));

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}", array.getShape());
        }
        return new NDList(array);
    }

    @Override
    /**
     * 处理输出
    */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray outputImg = list.singletonOrThrow();
        long[] shape = outputImg.getShape().getShape();

 // 兼容 [1, C, H, W] 与 [C, H, W]，不调用 squeeze（ONNX ndarray 会递归崩溃）
        int off = shape.length == 4 ? 1 : 0;
        int c = (int) shape[off];
        int h = (int) shape[off + 1];
        int w = (int) shape[off + 2];
        float[] data = outputImg.toFloatArray();

        // CHW [0, 1] -> HWC [0, 255] uint8，手动构建 BufferedImage
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int stride = h * w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int r = clampU8(data[idx]);
                int g = clampU8(data[idx + stride]);
                int b = clampU8(data[idx + 2 * stride]);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("                  : {}x{}", w, h);
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
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return null;
    }
}
