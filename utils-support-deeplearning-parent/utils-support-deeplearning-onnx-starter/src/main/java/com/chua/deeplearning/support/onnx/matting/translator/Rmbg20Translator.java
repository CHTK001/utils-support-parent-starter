package com.chua.deeplearning.support.onnx.matting.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
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

        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        if (width != targetWidth || height != targetHeight) {
            array = NDImageUtils.resize(array, targetWidth, targetHeight, Image.Interpolation.BILINEAR);
        }

        array = array.div(255.0f);
        array = array.transpose(2, 0, 1);

        NDArray mean = ctx.getNDManager().create(new float[]{0.485f, 0.456f, 0.406f}, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(new float[]{0.229f, 0.224f, 0.225f}, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);

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
        NDArray alpha = list.get(0);

        if (alpha.getShape().dimension() == 4) {
            alpha = alpha.get(0);
        }
        if (alpha.getShape().dimension() == 3) {
            alpha = alpha.get(0);
        }

        // RMBG-2.0     ONNX                             alpha matte                      min-max          
        alpha = alpha.clip(0f, 1f);
        BufferedImage alphaMask = toAlphaMask(alpha);

        return switch (mode) {
            case ALPHA_ONLY -> createAlphaOnlyImage(alphaMask);
            case RGB_BLACK_BG -> createRgbImage(alphaMask, 0);
            case RGB_WHITE_BG -> createRgbImage(alphaMask, 255);
            case RGBA -> createRgbaImage(alphaMask);
        };
    }

    private BufferedImage toAlphaMask(NDArray alpha) {
        Shape shape = alpha.getShape();
        int alphaHeight = (int) shape.get(0);
        int alphaWidth = (int) shape.get(1);
        float[] alphaValues = alpha.toFloatArray();

        BufferedImage mask = new BufferedImage(alphaWidth, alphaHeight, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = mask.getRaster();
        int index = 0;
        for (int y = 0; y < alphaHeight; y++) {
            for (int x = 0; x < alphaWidth; x++) {
                raster.setSample(x, y, 0, toAlpha255(alphaValues[index++]));
            }
        }

        if (alphaWidth == width && alphaHeight == height) {
            return mask;
        }

        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(mask, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

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

    private int blendChannel(int foreground, int background, int alpha, int inverseAlpha) {
        return (foreground * alpha + background * inverseAlpha + 127) / 255;
    }

    private int toAlpha255(float value) {
        float clipped = Math.max(0f, Math.min(1f, value));
        return Math.round(clipped * 255f);
    }

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
        return Batchifier.STACK;
    }
}
