package com.chua.deeplearning.support.onnx.matting.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
/**
* U2netSegTranslator类。
*
* @author CH
* @since 4.0.0
* @param mask mask
* @param bgValue bg值
* @return 创建rgb镜像的结果
 */

public final class U2netSegTranslator implements Translator<Image, Image> {

    private static final int SIZE = 320; // 大小
    private static final MattingTranslator.MattingMode DEFAULT_MODE = MattingTranslator.MattingMode.RGBA; // 默认mode

    private final MattingTranslator.MattingMode mode; // mode
    /**
    * U2netsegtranslator。
     */
    private int width, height;
    /**
    * U2netsegtranslator。
    * @param mode mode
     */
    private BufferedImage originalImage;

    /**
    * U2netSegTranslator。
     */
    public U2netSegTranslator() { this(DEFAULT_MODE); }
    /**
    * 处理输入。
    * @param ctx ctx
    * @param input 输入
    * @return 处理输入的结果
    * @param mode mode
     */
    public U2netSegTranslator(MattingTranslator.MattingMode mode) { this.mode = mode; }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        originalImage = toBufferedImage(input);
        BufferedImage resized = resizeTo(input, SIZE, SIZE);
        float[] data = new float[3 * SIZE * SIZE];
        int idx = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                data[idx] = ((rgb >>> 16) & 0xFF) / 255.0f;
                data[idx + SIZE * SIZE] = ((rgb >>> 8) & 0xFF) / 255.0f;
                data[idx + 2 * SIZE * SIZE] = (rgb & 0xFF) / 255.0f;
                idx++;
            }
        }
        NDArray arr = ctx.getNDManager().create(data, new Shape(1, 3, SIZE, SIZE));
        return new NDList(arr);
    /**
    * 处理输出。
    * @param ctx ctx
    * @param list 列表
    * @return 处理输出的结果
     */
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray alpha = list.get(0);
        Shape shape = alpha.getShape();
        long[] sh = shape.getShape();
        int h = (int) sh[sh.length - 2];
        int w = (int) sh[sh.length - 1];
        float[] vals = alpha.toFloatArray();
        for (int i = 0; i < vals.length; i++) {
            vals[i] = Math.max(0f, Math.min(1f, vals[i]));
        }
        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = mask.getRaster();
        int idx = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.setSample(x, y, 0, Math.round(vals[idx++] * 255f));
            }
        }
        if (w != width || h != height) {
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(mask, 0, 0, width, height, null);
            g.dispose();
            mask = resized;
        }
        return switch (mode) {
            case RGBA -> createRgbaImage(mask);
            case RGB_WHITE_BG -> createRgbImage(mask, 255);
            case ALPHA_ONLY -> createAlphaOnlyImage(mask);
            default -> createRgbImage(mask, 0);
        /**
        * 转为缓冲镜像。
        * @param input 输入
        * @return 转为缓冲镜像的结果
         */
        };
    }

    private BufferedImage toBufferedImage(Image input) {
        Object wrapped = input.getWrappedImage();
        if (wrapped instanceof BufferedImage bi) {
            return bi;
        }
        /**
        * resize转为。
        * @param input 输入
        * @param tw tw
        * @param th th
        * @return resize转为的结果
         */
        throw new IllegalStateException("无法获取 BufferedImage");
    }

    private BufferedImage resizeTo(Image input, int tw, int th) {
        BufferedImage src = toBufferedImage(input);
        if (src.getWidth() == tw && src.getHeight() == th) {
            return src;
        }
        BufferedImage resized = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        /**
        * 创建alphaonly镜像。
        * @param mask mask
        * @return 创建alphaonly镜像的结果
        * @param bgValue bg值
         */
        return resized;
    }

    private Image createAlphaOnlyImage(BufferedImage mask) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
            int a = mask.getRaster().getSample(x, y, 0);
            result.setRGB(x, y, (a << 16) | (a << 8) | a);
            }
            return ImageFactory.getInstance().fromImage(result);
        }
    }

    private Image createRgbaImage(BufferedImage mask) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
            int a = mask.getRaster().getSample(x, y, 0);
            int rgb = originalImage.getRGB(x, y) & 0x00FFFFFF;
            result.setRGB(x, y, (a << 24) | rgb);
            }
            return ImageFactory.getInstance().fromImage(result);
        }
    }

    private Image createRgbImage(BufferedImage mask, int bgValue) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
            int a = mask.getRaster().getSample(x, y, 0);
            int ia = 255 - a;
            int rgb = originalImage.getRGB(x, y);
            int r = (((rgb >>> 16) & 0xFF) * a + bgValue * ia) / 255;
            int g = (((rgb >>> 8) & 0xFF) * a + bgValue * ia) / 255;
            int b = ((rgb & 0xFF) * a + bgValue * ia) / 255;
            result.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
            return ImageFactory.getInstance().fromImage(result);
        }
    }

    @Override
    public Batchifier getBatchifier() { return null; }
}