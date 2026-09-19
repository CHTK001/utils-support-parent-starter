package com.chua.deeplearning.support.onnx.matting.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
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
 * 通用抠图 Translator，支持 是否net/U2Net/Anime/Cloth/Human 等分割模型。
 * 使用 AWT Graphics2D 替代 nd镜像工具.resize（ORT 引擎不支持后者）。
 *
 * @author CH
 * @since 2025/10/22
 */
public final class MattingTranslator implements Translator<Image, Image> {

    private static final int DEFAULT_TARGET_SIZE = 512; // 默认Target大小
    private static final int U2NET_TARGET_SIZE = 320; // u2netTarget大小

    private final int targetWidth; // Targetwidth
    private final int targetHeight; // Targetheight
    private final boolean needResize; // needresize
    private final MattingMode mode; // mode
    private final boolean normalize; // normalize

    private int width; // width
    private int height; // height
    private BufferedImage originalImage; // 原始镜像
    private boolean lowInformationInput; // low信息输入
    /**
     * MattingMode枚举。
     *
     * @author CH
     * @since 4.0.0
     */

    public enum MattingMode {
        ALPHA_ONLY,
        RGBA,
        RGB_BLACK_BG,
        RGB_WHITE_BG
    }

    /**
     * 无参构造，默认 512×512，RGBA 输出，需归一化。
     * @return MattingTranslator的结果
     */
    public MattingTranslator() {
        this(DEFAULT_TARGET_SIZE, DEFAULT_TARGET_SIZE, MattingMode.RGBA, true);
    }

    /**
     * 指定尺寸和模式。
     *
     * @param targetWidth  目标宽度
     * @param targetHeight 目标高度
     * @param mode         输出模式
     * @param normalize    是否做 镜像net 归一化
     * @return MattingTranslator的结果
     */
    public MattingTranslator(int targetWidth, int targetHeight, MattingMode mode, boolean normalize) {
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.mode = mode;
        this.normalize = normalize;
        this.needResize = true;
    }

    /**
     * 从 detection配置 构造，自动识别模型类型。
     *
     * @param configuration 配置
     * @param image 镜像
     * @param input 输入
     */
    public MattingTranslator(DetectionConfiguration configuration) {
        this(resolveTargetSize(configuration), resolveTargetSize(configuration), resolveMode(configuration), resolveNormalize(configuration));
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        originalImage = toBufferedImage(input);
        lowInformationInput = isLowInformationImage(originalImage);

        BufferedImage resized = originalImage;
        int tw = targetWidth, th = targetHeight;
        if (needResize && (width != tw || height != th)) {
            resized = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(originalImage, 0, 0, tw, th, null);
            g.dispose();
        }

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

        if (normalize) {
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
        }

        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, th, tw));
        return new NDList(array);
    /**
     * 处理输出。
     * @param ctx ctx
     * @param list 列表
     * @return 处理输出的结果
     * @param input 输入
     */
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        if (lowInformationInput) {
            return createLowInformationFallback();
        }
        NDArray alpha = list.get(0);
        Shape shape = alpha.getShape();
        long[] sh = shape.getShape();
        int alphaHeight = sh.length >= 2 ? (int) sh[sh.length - 2] : 1;
        int alphaWidth = sh.length >= 1 ? (int) sh[sh.length - 1] : 1;
        float[] alphaValues = alpha.toFloatArray();

        int expected = alphaHeight * alphaWidth;
        if (alphaValues.length > expected * 2) {
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
        int index = 0;
        for (int y = 0; y < alphaHeight; y++) {
            for (int x = 0; x < alphaWidth; x++) {
                raster.setSample(x, y, 0, Math.round(alphaValues[index++] * 255f));
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
     * 转为BufferedImage。
     *
     * @param input 方法入参 input
     * @return BufferedImage 对象
     */
    private BufferedImage toBufferedImage(Image input) {
        Object wrapped = input.getWrappedImage();
        if (wrapped instanceof BufferedImage bufferedImage) {
            return bufferedImage;
        }
        throw new IllegalStateException("无法获取 BufferedImage");
    /**
     * 创建alphaonly镜像。
     * @param alphaMask alphamask
     * @return 创建alphaonly镜像的结果
     */
    }

    /**
     * 创建AlphaOnlyImage。
     *
     * @param alphaMask 方法入参 alphaMask
     * @return Image 对象
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
    /**
     * 创建rgba镜像。
     * @param alphaMask alphamask
     * @return 创建rgba镜像的结果
     */
    }

    /**
     * 创建RgbaImage。
     *
     * @param alphaMask 方法入参 alphaMask
     * @return Image 对象
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
    /**
     * 创建rgb镜像。
     * @param alphaMask alphamask
     * @param bgValue bg值
     * @return 创建rgb镜像的结果
     */
    }

    /**
     * 创建RgbImage。
     *
     * @param alphaMask 方法入参 alphaMask
     * @param bgValue bg值，不允许为 null
     * @return Image 对象
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
    /**
     * blend通道。
     * @param foreground foreground
     * @param background background
     * @param alpha alpha
     * @param inverseAlpha inversealpha
     * @return blend通道的结果
     */
    }

    /**
     * blendChannel。
     *
     * @param foreground 方法入参 foreground
     * @param background 方法入参 background
     * @param alpha 方法入参 alpha
     * @param inverseAlpha 方法入参 inverseAlpha
     * @return 结果数值
     */
    private int blendChannel(int foreground, int background, int alpha, int inverseAlpha) {
        return (foreground * alpha + background * inverseAlpha + 127) / 255;
    /**
     * 创建low信息降级。
     * @return 创建low信息降级的结果
     * @param configuration 配置
     * @param image 镜像
     */
    }

    /**
     * 创建LowInformationFallback。
     *
     * @return Image 对象
     */
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

    /**
     * 是否LowInformationImage。
     *
     * @param image 方法入参 image
     * @return 是否成功（true 表示成功）
     */
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

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 解析目标大小。
     *
     * @param configuration 配置，不允许为 null
     * @return 结果数值
     */
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

    /**
     * 解析模式。
     *
     * @param configuration 配置，不允许为 null
     * @return Matting模式 对象
     */
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

    /**
     * 解析Normalize。
     *
     * @param configuration 配置，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private static boolean resolveNormalize(DetectionConfiguration configuration) {
        if (configuration == null || configuration.modelName() == null) {
            return true;
        }
        String modelName = configuration.modelName().trim().toLowerCase();
        return !modelName.contains("isnet") && !modelName.contains("anime")
                && !modelName.contains("cloth") && !modelName.contains("human-seg");
    }
}
