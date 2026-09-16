package com.chua.deeplearning.support.onnx.matting.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.Map;


/**
* BRIA RMBG-2.0                                  
*
* @author CH
* @since 2026/04/04
 */
@Slf4j
public final class Rmbg20Translator implements Translator<Image, Image> {

    /**
    *                              
     */
    private static final int DEFAULT_TARGET_SIZE = 1024;

    /**
    *              
     */
    private final MattingTranslator.MattingMode mode;

    /**
    *              
     */
    private final int targetWidth;

    /**
    *              
     */
    private final int targetHeight;

    /**
    *              
     */
    private int width;

    /**
    *              
     */
    private int height;

    /**
    *                              
     */
    private BufferedImage originalImage;

    /**
    *              
     */
    public Rmbg20Translator() {
        this(MattingTranslator.MattingMode.RGBA, resolveTargetSize(null));
    }

    /**
    *                              
    *
    * @param arguments                     
     */
    public Rmbg20Translator(Map<String, ?> arguments) {
        this(resolveMode(arguments), resolveTargetSize(arguments));
    }

    /**
    *                              
    *
    * @param mode          
    * @param targetSize          
     */
    private Rmbg20Translator(MattingTranslator.MattingMode mode, int targetSize) {
        this.mode = mode;
        this.targetWidth = targetSize;
        this.targetHeight = targetSize;
    }

    /**
    *                                                   {@link NDList}   
    *
    * @param ctx                               {@code NDManager}          
    * @param input                                RGB          
    * @return                                            {@code [1, 3, H, W]}
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        originalImage = (BufferedImage) input.getWrappedImage();

        // AWT resize (avoids NDImageUtils.resize which ORT engine doesn't support)
        BufferedImage resized = originalImage;
        int tw = targetWidth, th = targetHeight;
        if (width != tw || height != th) {
            resized = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(originalImage, 0, 0, tw, th, null);
            g.dispose();
        }

        // Manual pixel normalization
        float[] data = new float[3 * tw * th];
        int idx = 0;
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int rgb = resized.getRGB(x, y);
                data[idx] = ((rgb >>> 16) & 0xFF) / 255.0f;
                data[idx + tw * th] = ((rgb >>> 8) & 0xFF) / 255.0f;
                data[idx + 2 * tw * th] = (rgb & 0xFF) / 255.0f;
                idx++;
            }
        }

 // 镜像net normalize
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        int total = tw * th;
        for (int c = 0; c < 3; c++) {
            int offset = c * total;
            float m = mean[c], s = std[c];
            for (int i = 0; i < total; i++) {
                data[offset + i] = (data[offset + i] - m) / s;
            }
        }

        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, th, tw));
        return new NDList(array);
    }

    /**
    *                                                  {@link Image}   
    *
    * @param ctx                    
    * @param list                                                                 {@code [1, C, H, W]}     {@code [C, H, W}
    * @return                                                 
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray alpha = list.getFirst();

        // Flatten to float[] and reconstruct as 2D (avoid NDArray.get which ORT engine doesn't support)
        Shape shape = alpha.getShape();
        long[] sh = shape.getShape();
        int alphaHeight = sh.length >= 2 ? (int) sh[sh.length - 2] : 1;
        int alphaWidth = sh.length >= 1 ? (int) sh[sh.length - 1] : 1;
        float[] alphaValues = alpha.toFloatArray();

        // Reject small outputs (e.g. if shape is [1,3,1024,1024] -> actual alpha is 2D)
        int expected = alphaHeight * alphaWidth;
        if (alphaValues.length > expected * 2) {
 // 模型 输出 是否 multi-通道, pick 第一个 通道
            alphaHeight = (int) sh[sh.length - 2];
            alphaWidth = (int) sh[sh.length - 1];
            expected = alphaHeight * alphaWidth;
            float[] single = new float[expected];
            System.arraycopy(alphaValues, 0, single, 0, expected);
            alphaValues = single;
        }

        for (int i = 0; i < alphaValues.length; i++) {
            alphaValues[i] = Math.max(0f, Math.min(1f, alphaValues[i]));
        }

        BufferedImage alphaMask = new BufferedImage(alphaWidth, alphaHeight, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = alphaMask.getRaster();
        int idx = 0;
        for (int y = 0; y < alphaHeight; y++) {
            for (int x = 0; x < alphaWidth; x++) {
                raster.setSample(x, y, 0, Math.round(alphaValues[idx++] * 255f));
            }
        }

        if (alphaWidth != width || alphaHeight != height) {
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(alphaMask, 0, 0, width, height, null);
            graphics.dispose();
            alphaMask = resized;
        }

        return switch (mode) {
            case ALPHA_ONLY -> createAlphaOnlyImage(alphaMask);
            case RGB_BLACK_BG -> createRgbImage(alphaMask, 0);
            case RGB_WHITE_BG -> createRgbImage(alphaMask, 255);
            case RGBA -> createRgbaImage(alphaMask);
        };
    }

    /**
    * 创建alphaonly镜像
    *
    * @param alphaMask alphamask
    * @return 创建alphaonly镜像的结果
     */
    private Image createAlphaOnlyImage(BufferedImage alphaMask) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = alphaMask.getRaster().getSample(x, y, 0);
                int gray = (alpha << 16) | (alpha << 8) | alpha;
                result.setRGB(x, y, gray);
            }
        }
        return ImageFactory.getInstance().fromImage(result);
    }

    /**
    * 创建rgba镜像
    *
    * @param alphaMask alphamask
    * @return 创建rgba镜像的结果
     */
    private Image createRgbaImage(BufferedImage alphaMask) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = alphaMask.getRaster().getSample(x, y, 0);
                int rgb = originalImage.getRGB(x, y) & 0x00FFFFFF;
                result.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        return ImageFactory.getInstance().fromImage(result);
    }

    /**
    * 创建rgb镜像
    *
    * @param alphaMask alphamask
    * @param bgValue bg值
    * @return 创建rgb镜像的结果
     */
    private Image createRgbImage(BufferedImage alphaMask, int bgValue) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = alphaMask.getRaster().getSample(x, y, 0);
                int inverseAlpha = 255 - alpha;
                int rgb = originalImage.getRGB(x, y);
                int red = blendChannel((rgb >>> 16) & 0xFF, bgValue, alpha, inverseAlpha);
                int green = blendChannel((rgb >>> 8) & 0xFF, bgValue, alpha, inverseAlpha);
                int blue = blendChannel(rgb & 0xFF, bgValue, alpha, inverseAlpha);
                result.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return ImageFactory.getInstance().fromImage(result);
    }

    /**
    * blend通道
    *
    * @param foreground foreground
    * @param background background
    * @param alpha alpha
    * @param inverseAlpha inversealpha
    * @return blend通道的结果
     */
    private int blendChannel(int foreground, int background, int alpha, int inverseAlpha) {
        return (foreground * alpha + background * inverseAlpha + 127) / 255;
    }

    /**
    * 转为alpha
    *
    * @param value 值
    * @return 转为alpha255的结果
     */
    private int toAlpha255(float value) {
        float clipped = Math.max(0f, Math.min(1f, value));
        return Math.round(clipped * 255f);
    }

    /**
    * 解析Mode
    *
    * @param arguments 参数
    * @return resolveMode的结果
     */
    private static MattingTranslator.MattingMode resolveMode(Map<String, ?> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return MattingTranslator.MattingMode.RGBA;
        }

        Object value = arguments.get("mode");
        if (value == null) {
            return MattingTranslator.MattingMode.RGBA;
        }

        try {
            return MattingTranslator.MattingMode.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return MattingTranslator.MattingMode.RGBA;
        }
    }

    /**
    * 解析Target获取大小
    *
    * @param arguments 参数
    * @return resolveTarget大小的结果
     */
    private static int resolveTargetSize(Map<String, ?> arguments) {
        Object configured = arguments == null ? null : arguments.get("targetSize");
        if (configured == null) {
            configured = System.getProperty("rmbg20.target-size");
        }
        if (configured == null) {
            return DEFAULT_TARGET_SIZE;
        }
        try {
            int value = Integer.parseInt(String.valueOf(configured).trim());
            return value > 0 ? value : DEFAULT_TARGET_SIZE;
        } catch (NumberFormatException ignored) {
            return DEFAULT_TARGET_SIZE;
        }
    }

    /**
    *                           
    *
    * @return Batchifier          
     */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
