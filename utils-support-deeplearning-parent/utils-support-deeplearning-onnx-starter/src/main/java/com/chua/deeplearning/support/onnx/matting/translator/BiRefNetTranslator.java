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
import java.util.Map;


/**
 * BiRefNet 高分辨率二值图像分割（前景抠图）Translator。
 *
 * <p>输入：任意尺寸 RGB 图像 → resize 到 512×512（small）或 1024×1024（large）
 * → ImageNet 归一化 → BiRefNet ONNX → 输出 [1, 1, H, W] 软 mask（0~1）。</p>
 *
 * <p>输出：RGBA 透明背景图，或用 MattingMode 指定黑底/白底。</p>
 *
 * <p>BiRefNet 小模型（Swin-T，~44M，512×512）嵌入式；
 * 大模型（Swin-L，~0.2B，1024×1024）通过 downloadUrl 自动下载。</p>
 *
 * @author CH
 * @since 2026/09/15
 */
public final class BiRefNetTranslator implements Translator<Image, Image> {

    /** 512 目标尺寸。 */
    private static final int SIZE_512 = 512;

    /** 1024 目标尺寸。 */
    private static final int SIZE_1024 = 1024;

    /** 模型标识。 */
    private final int targetSize;

    /** 输出模式。 */
    private final MattingTranslator.MattingMode mode;

    /** 当前帧原始宽度。 */
    private int width;

    /** 当前帧原始高度。 */
    private int height;

    /** 当前帧原始图像。 */
    private BufferedImage originalImage;


    /**
    * 构造 512×512 小模型 Translator。
    */
    public BiRefNetTranslator() {
        this(SIZE_512, MattingTranslator.MattingMode.RGBA);
    }


    /**
    * 构造指定尺寸和输出模式的 Translator。
    *
    * @param targetSize 目标尺寸（512 或 1024）
    * @param mode 输出模式
    */
    public BiRefNetTranslator(int targetSize, MattingTranslator.MattingMode mode) {
        this.targetSize = targetSize;
        this.mode = mode;
    }


    /**
    * 从 arguments Map 构造。
    *
    * @param arguments 参数（targetSize 可选，默认 512；mode 可选，默认 RGBA）
    */
    public BiRefNetTranslator(Map<String, ?> arguments) {
        int size = SIZE_512;
        MattingTranslator.MattingMode m = MattingTranslator.MattingMode.RGBA;
        if (arguments != null) {
            Object ts = arguments.get("targetSize");
            if (ts != null) {
                size = Integer.parseInt(String.valueOf(ts).trim());
            }
            Object mv = arguments.get("mode");
            if (mv != null) {
                m = MattingTranslator.MattingMode.valueOf(String.valueOf(mv).trim().toUpperCase());
            }
        }
        this.targetSize = size;
        this.mode = m;
    }


    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        originalImage = (BufferedImage) input.getWrappedImage();

        BufferedImage resized = originalImage;
        int tw = targetSize, th = targetSize;
        if (width != tw || height != th) {
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

        // ImageNet 归一化
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


    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.getFirst();
        float[] values = output.toFloatArray();
        long[] shape = output.getShape().getShape();
        int h = shape.length >= 2 ? (int) shape[shape.length - 2] : 1;
        int w = shape.length >= 1 ? (int) shape[shape.length - 1] : 1;

        // 如果输出通道 > 1，取第一个通道（mask）
        if (values.length > h * w) {
            float[] single = new float[h * w];
            System.arraycopy(values, 0, single, 0, h * w);
            values = single;
        }

        for (int i = 0; i < values.length; i++) {
            values[i] = Math.max(0f, Math.min(1f, values[i]));
        }

        BufferedImage alphaMask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = alphaMask.getRaster();
        int idx = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.setSample(x, y, 0, Math.round(values[idx++] * 255f));
            }
        }

        if (w != width || h != height) {
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(alphaMask, 0, 0, width, height, null);
            graphics.dispose();
            alphaMask = resized;
        }

        return switch (mode) {
            case ALPHA_ONLY -> createAlphaOnly(alphaMask);
            case RGB_BLACK_BG -> createRgb(alphaMask, 0);
            case RGB_WHITE_BG -> createRgb(alphaMask, 255);
            case RGBA -> createRgba(alphaMask);
        };
    }


    /**
    * 创建 ALPHA_ONLY 灰度 mask。
    *
    * @param alphaMask alpha mask
    * @return 创建 ALPHA_ONLY 灰度 mask 的结果
    */
    private Image createAlphaOnly(BufferedImage alphaMask) {
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
    * 创建 RGBA 透明背景。
    *
    * @param alphaMask alpha mask
    * @return 创建 RGBA 透明背景的结果
    */
    private Image createRgba(BufferedImage alphaMask) {
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
    * 创建指定背景色 RGB。
    *
    * @param alphaMask alpha mask
    * @param bgValue 背景色（0 黑 / 255 白）
    * @return 创建指定背景色 RGB 的结果
    */
    private Image createRgb(BufferedImage alphaMask, int bgValue) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = alphaMask.getRaster().getSample(x, y, 0);
                int inv = 255 - alpha;
                int rgb = originalImage.getRGB(x, y);
                int r = blend((rgb >>> 16) & 0xFF, bgValue, alpha, inv);
                int g = blend((rgb >>> 8) & 0xFF, bgValue, alpha, inv);
                int b = blend(rgb & 0xFF, bgValue, alpha, inv);
                result.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return ImageFactory.getInstance().fromImage(result);
    }


    /**
    * 单通道 alpha blend。
    *
    * @param fg 前景
    * @param bg 背景
    * @param alpha alpha
    * @param inv 反向 alpha
    * @return 单通道 alpha blend 的结果
    */
    private static int blend(int fg, int bg, int alpha, int inv) {
        return (fg * alpha + bg * inv + 127) / 255;
    }


    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
