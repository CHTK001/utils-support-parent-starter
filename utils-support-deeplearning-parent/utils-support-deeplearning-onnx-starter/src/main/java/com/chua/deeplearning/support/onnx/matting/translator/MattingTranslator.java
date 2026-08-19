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
import com.chua.deeplearning.support.ai.DetectionConfiguration;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;


/**
 *                            
 *
 * @since 2025/10/22
 */
public final class MattingTranslator implements Translator<Image, Image> {

    /**
     *                              
     */
    private static final int DEFAULT_TARGET_SIZE = 512;

    /**
     * U2NET                              
     */
    private static final int U2NET_TARGET_SIZE = 320;

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
    private final boolean needResize;

    /**
     *              
     */
    private final MattingMode mode;

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
    private boolean lowInformationInput;

    /**
     *              
      * @author CH
     */
    public enum MattingMode {
        /**
         *              
         */
        ALPHA_ONLY,
        /**
         * RGBA              
         */
        RGBA,
        /**
         * RGB              
         */
        RGB_BLACK_BG,
        /**
         * RGB              
         */
        RGB_WHITE_BG
    }

    /**
     *                              
     *
     * @param targetWidth          
     * @param targetHeight         
     * @param mode              
     */
    public MattingTranslator(int targetWidth, int targetHeight, MattingMode mode) {
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.mode = mode;
        this.needResize = true;
    }

    /**
     *                              
     */
    public MattingTranslator() {
        this(DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE, MattingMode.RGBA);
    }

    /**
     *                              
     *
     * @param configuration                 
     */
    public MattingTranslator(DetectionConfiguration configuration) {
        this(resolveTargetSize(configuration), resolveTargetSize(configuration), resolveMode(configuration));
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
        originalImage = toBufferedImage(input, ctx);
        lowInformationInput = isLowInformationImage(originalImage);

        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        if (needResize && (width != targetWidth || height != targetHeight)) {
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
        if (lowInformationInput) {
            return createLowInformationFallback();
        }
        NDArray alpha = list.get(0);
        if (alpha.getShape().dimension() == 4) {
            alpha = alpha.get(0);
        }
        if (alpha.getShape().dimension() == 3) {
            alpha = alpha.get(0);
        }

        NDArray alphaMin = alpha.min();
        NDArray alphaMax = alpha.max();
        if (!alphaMin.equals(alphaMax)) {
            alpha = alpha.sub(alphaMin).div(alphaMax.sub(alphaMin));
        }
        alpha = alpha.clip(0f, 1f);
        BufferedImage alphaMask = toAlphaMask(alpha);

        return switch (mode) {
            case ALPHA_ONLY -> createAlphaOnlyImage(alphaMask);
            case RGB_BLACK_BG -> createRgbImage(alphaMask, 0);
            case RGB_WHITE_BG -> createRgbImage(alphaMask, 255);
            case RGBA -> createRgbaImage(alphaMask);
        };
    }

    /** ToBufferedImage */
    private BufferedImage toBufferedImage(Image input, TranslatorContext ctx) {
        Object wrapped = input.getWrappedImage();
        if (wrapped instanceof BufferedImage bufferedImage) {
            return bufferedImage;
        }
        Object converted = ImageFactory.getInstance()
                .fromNDArray(input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR))
                .getWrappedImage();
        if (converted instanceof BufferedImage bufferedImage) {
            return bufferedImage;
        }
        throw new IllegalStateException("                               BufferedImage");
    }

    /** ToAlphaMask */
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

    /** 创建AlphaOnlyImage */
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

    /** 创建RgbaImage */
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

    /** 创建RgbImage */
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

    /** BlendChannel */
    private int blendChannel(int foreground, int background, int alpha, int inverseAlpha) {
        return (foreground * alpha + background * inverseAlpha + 127) / 255;
    }

    /** ToAlpha */
    private int toAlpha255(float value) {
        float clipped = Math.max(0f, Math.min(1f, value));
        return Math.round(clipped * 255f);
    }

    /** 创建LowInformationFallback */
    private Image createLowInformationFallback() {
        return switch (mode) {
            case RGBA -> ImageFactory.getInstance()
                    .fromImage(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB));
            case RGB_WHITE_BG -> {
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    graphics.setColor(java.awt.Color.WHITE);
                    graphics.fillRect(0, 0, width, height);
                } finally {
                    graphics.dispose();
                }
                yield ImageFactory.getInstance().fromImage(image);
            }
            default -> ImageFactory.getInstance()
                    .fromImage(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
        };
    }

    /** 是否LowInformationImage */
    private boolean isLowInformationImage(BufferedImage image) {
        if (image == null) {
            return false;
        }
        int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 128);
        long count = 0L;
        double sum = 0D;
        double sumSquares = 0D;
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >>> 16) & 0xFF;
                int green = (rgb >>> 8) & 0xFF;
                int blue = rgb & 0xFF;
                double gray = (red * 0.299d) + (green * 0.587d) + (blue * 0.114d);
                sum += gray;
                sumSquares += gray * gray;
                count++;
            }
        }
        if (count == 0L) {
            return false;
        }
        double mean = sum / count;
        double variance = (sumSquares / count) - (mean * mean);
        return variance <= 12D;
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

    /** 解析Target获取大小 */
    private static int resolveTargetSize(DetectionConfiguration configuration) {
        if (configuration == null || configuration.modelName() == null) {
            return DEFAULT_TARGET_SIZE;
        }
        String modelName = configuration.modelName().trim().toLowerCase();
        if ("u2net".equals(modelName) || "u2netp".equals(modelName)) {
            return U2NET_TARGET_SIZE;
        }
        return DEFAULT_TARGET_SIZE;
    }

    /** 解析Mode */
    private static MattingMode resolveMode(DetectionConfiguration configuration) {
        if (configuration == null || configuration.modelName() == null) {
            return MattingMode.RGBA;
        }
        String modelName = configuration.modelName().trim().toLowerCase();
        if ("u2net".equals(modelName) || "u2netp".equals(modelName)) {
            return MattingMode.RGB_BLACK_BG;
        }
        return MattingMode.RGBA;
    }
}
