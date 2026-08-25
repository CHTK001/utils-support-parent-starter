package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;

/**
 * ONNX 引擎 NDArray 预处理工具。
 *
 * <p>性能设计要点：
 * <ul>
 *   <li>不经过 {@code Image.toNDArray(...)} 创建 OrtNDArray（避免不支持的算子回退失败 + 开销）；</li>
 *   <li>直接通过 {@link Image#getWrappedImage()} 拿到 {@link BufferedImage}，
 *       使用其底层 {@link java.awt.image.Raster#getDataBuffer()} 直接读取字节数组，
 *       比 {@code BufferedImage.getRGB(...)}（逐像素 int 转换 + 装箱）快 5-10x；</li>
 *   <li>使用 {@code Graphics2D.drawImage(...)} 完成 resize（JDK 内置快速路径，
 *       比 ImageIO + bilinear 采样快）；</li>
 *   <li>CHW 转换与归一化使用单层嵌套循环，避免 NDArray.transpose/sub/div 等不支持的 ONNX 算子；</li>
 *   <li>最终用 {@code NDManager.create(FloatBuffer, Shape, FLOAT32)} 一次性建好输入。</li>
 * </ul>
 * </p>
 *
 * <p>ONNX Runtime 引擎的 NDArray 仅支持有限的算子（transpose / resize 等会 fallback 到
 * numpy 引擎并抛 UnsupportedOperationException），且 Java 像素处理已经足够快 —
 * 这里刻意避开所有高级 NDArray 算子。</p>
 *
 * @author CH
 * @since 2026-08-08
 */
public final class OnnxImageProcessor {

    /** 创建 OnnxImageProcessor 实例 */
    private OnnxImageProcessor() {
    }

    /**
     * 将 {@link Image} 预处理为模型输入张量。
     *
     * @param image     输入图像
     * @param width     目标宽度
     * @param height    目标高度
     * @param channels  通道数（3=RGB，1=灰度）
     * @param grayscale true 时按灰度读取（BGR/ARGB 转灰度系数 0.299R+0.587G+0.114B）
     * @param mean      减去的均值（每通道相同），RGB 顺序
     * @param scale     缩放因子（典型 1.0f 或 1/128.0f）
     * @return          [1, channels, height, width] 的 NDList
     */
    public static NDList toModelInput(Image image, int width, int height,
                                       int channels, boolean grayscale,
                                       float mean, float scale, NDManager ctxManager) {
        BufferedImage src = unwrap(image);
        if (src.getWidth() != width || src.getHeight() != height) {
            src = resizeFast(src, width, height);
        }

        // 将 (pixel - mean) * scale 等价为: pixel * scale - mean * scale
        // 即 mean 参数需要预先乘以 scale，避免 fillChw 内重复减 mean
        float scaledMean = mean * scale;

        float[] chw = new float[channels * height * width];
        fillChw(src, chw, channels, grayscale, scaledMean, scale);

        Shape shape = new Shape(1, channels, height, width);
        NDArray array = ctxManager.create(FloatBuffer.wrap(chw), shape, DataType.FLOAT32);
        return new NDList(array);
    }

    /**
     * 仅做 HWC float 数组提取（不创建 NDList）。
     */
    public static float[] toChwFloat(Image image, int width, int height, int channels,
                                      boolean grayscale, float scale) {
        BufferedImage src = unwrap(image);
        if (src.getWidth() != width || src.getHeight() != height) {
            src = resizeFast(src, width, height);
        }
        float[] chw = new float[channels * height * width];
        fillChw(src, chw, channels, grayscale, 0.0f, scale);
        return chw;
    }

    /**
     * 从 Image 取底层 BufferedImage。DJL 默认 {@code BufferedImageFactory} 返回
     * BufferedImage 包装，其他实现如有不同可由调用方重写。
     */
    private static BufferedImage unwrap(Image image) {
        Object wrapped = image.getWrappedImage();
        if (wrapped instanceof BufferedImage bi) {
            return bi;
        }
        // 回退：通过 toNDArray 拿到的字节再还原（这里理论上不会触发）
        throw new IllegalStateException(
                "Unsupported Image implementation: " + wrapped.getClass().getName());
    }

    /**
     * 快速 resize：基于 {@code Graphics2D.drawImage}，对 RGB/灰度图像启用
     * {@code RenderingHints.VALUE_INTERPOLATION_BILINEAR}。比
     * {@code BufferedImage.getScaledInstance} 更可控、避免额外的 image buffer 分配。
     */
    private static BufferedImage resizeFast(BufferedImage src, int w, int h) {
        int type = src.getType() == BufferedImage.TYPE_CUSTOM
                ? BufferedImage.TYPE_INT_RGB : src.getType();
        BufferedImage dst = new BufferedImage(w, h, type);
        var g = dst.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_SPEED);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * 直接读取 BufferedImage 像素并填充到 CHW float 数组。
     * 对常见类型（3BYTE_BGR / INT_RGB / INT_ARGB / BYTE_GRAY）走快速路径，
     * 其他类型回退到 {@code getRGB()}。
     */
    private static void fillChw(BufferedImage img, float[] chw,
                                 int channels, boolean grayscale,
                                 float mean, float scale) {
        int w = img.getWidth();
        int h = img.getHeight();
        int type = img.getType();
        if (channels == 3 && !grayscale) {
            if (type == BufferedImage.TYPE_3BYTE_BGR) {
                fillChwBgr(img, chw, w, h, mean, scale);
                return;
            }
            // 仅 INT 缓冲走 int 直读；4BYTE_ABGR 为字节缓冲，落入通用 getRGB 路径
            if (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB) {
                fillChwArgb(img, chw, w, h, mean, scale, type == BufferedImage.TYPE_INT_ARGB);
                return;
            }
        }
        if (channels == 1 && grayscale && type == BufferedImage.TYPE_BYTE_GRAY) {
            fillChwGray(img, chw, w, h, mean, scale);
            return;
        }
        // 回退：使用 getRGB
        fillChwGeneric(img, chw, w, h, channels, grayscale, mean, scale);
    }

    /**
     * TYPE_3BYTE_BGR：DataBufferByte，每个像素 3 字节 (B, G, R)。
     */
    private static void fillChwBgr(BufferedImage img, float[] chw,
                                    int w, int h, float mean, float scale) {
        byte[] data = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
        // CHW 顺序：先写 R 通道，再 G，再 B
        int rOff = 0;
        int gOff = w * h;
        int bOff = 2 * w * h;
        for (int y = 0; y < h; y++) {
            int row = y * w * 3;
            for (int x = 0; x < w; x++) {
                int idx = row + x * 3;
                chw[rOff + y * w + x] = (data[idx + 2] & 0xFF) * scale - mean; // R
                chw[gOff + y * w + x] = (data[idx + 1] & 0xFF) * scale - mean; // G
                chw[bOff + y * w + x] = (data[idx + 0] & 0xFF) * scale - mean; // B
            }
        }
    }

    /**
     * TYPE_INT_RGB / TYPE_INT_ARGB：DataBufferInt，每个像素 4 字节 (R, G, B, A/PAD)。
     */
    private static void fillChwArgb(BufferedImage img, float[] chw,
                                     int w, int h, float mean, float scale,
                                     boolean hasAlpha) {
        int[] data = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int rOff = 0;
        int gOff = w * h;
        int bOff = 2 * w * h;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int p = data[row + x];
                // ARGB 或 RGB：R 在最高字节（位 16）
                chw[rOff + row + x] = ((p >> 16) & 0xFF) * scale - mean;
                chw[gOff + row + x] = ((p >> 8) & 0xFF) * scale - mean;
                chw[bOff + row + x] = (p & 0xFF) * scale - mean;
            }
        }
    }

    /**
     * TYPE_BYTE_GRAY：DataBufferByte，每像素 1 字节。
     */
    private static void fillChwGray(BufferedImage img, float[] chw,
                                     int w, int h, float mean, float scale) {
        byte[] data = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < w * h; i++) {
            chw[i] = (data[i] & 0xFF) * scale - mean;
        }
    }

    /**
     * 回退路径：调用 getRGB 逐像素（慢但兼容所有 BufferedImage 类型）。
     */
    private static void fillChwGeneric(BufferedImage img, float[] chw,
                                        int w, int h, int channels,
                                        boolean grayscale, float mean, float scale) {
        int rOff = 0;
        int gOff = w * h;
        int bOff = 2 * w * h;
        int gPlane = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                if (channels == 1) {
                    float gray = grayscale
                            ? (0.299f * r + 0.587f * g + 0.114f * b)
                            : r;
                    chw[gPlane + y * w + x] = gray * scale - mean;
                } else {
                    chw[rOff + y * w + x] = r * scale - mean;
                    chw[gOff + y * w + x] = g * scale - mean;
                    chw[bOff + y * w + x] = b * scale - mean;
                }
            }
        }
    }
}