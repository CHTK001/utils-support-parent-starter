package com.chua.common.support.image.processor;

import com.chua.common.support.image.ImageProcessor;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiOrder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * 基于 JDK AWT 的默认图像处理器
 *
 * <p>无需任何原生依赖，作为 {@link ImageProcessor} 的兜底实现。
 * 性能弱于 OpenCV / Rust 原生实现，但保证任何环境均可运行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("image-processor")
@SpiOrder(-100)
public class JdkImageProcessor implements ImageProcessor {

    @Override
    /** 处理 */
    public byte[] process(byte[] imageData, String operation, Map<String, Object> params) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                throw new IllegalArgumentException("无法解码图像数据");
            }
            BufferedImage result = switch (operation) {
                case "resize" -> resize(image, params);
                case "grayscale" -> grayscale(image);
                case "rotate" -> rotate(image, params);
                case "crop" -> crop(image, params);
                case "blur" -> blur(image, params);
                case "flip" -> flip(image, params);
                case "brightness" -> brightness(image, params);
                case "contrast" -> contrast(image, params);
                case "border" -> border(image, params);
                case "binarize" -> binarize(image, params);
                case "denoise" -> denoise(image, params);
                case "erode" -> erode(image, params);
                case "dilate" -> dilate(image, params);
                case "edge" -> edge(image, params);
                default -> image;
            };
            return encode(result, params);
        } catch (IOException e) {
            throw new IllegalArgumentException("图像处理失败", e);
        }
    }

    /**
     * 缩放图像
     *
     * @param image  源图像
     * @param params 参数：width / height
     * @return 缩放后的图像
     */
    private BufferedImage resize(BufferedImage image, Map<String, Object> params) {
        int width = toInt(params.get("width"), 200);
        int height = toInt(params.get("height"), 200);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return result;
    }

    /**
     * 转为灰度图像
     *
     * @param image 源图像
     * @return 灰度图像
     */
    private BufferedImage grayscale(BufferedImage image) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = result.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return result;
    }

    /**
     * 旋转图像
     *
     * @param image  源图像
     * @param params 参数：angle（度，支持 90 的整数倍）
     * @return 旋转后的图像
     */
    private BufferedImage rotate(BufferedImage image, Map<String, Object> params) {
        int angle = toInt(params.get("angle"), 90) % 360;
        if (angle < 0) {
            angle += 360;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        if (angle == 0) {
            return image;
        }
        if (angle == 180) {
            return rotate180(image);
        }
        // 90° / 270°：宽高互换
        if (angle == 90 || angle == 270) {
            BufferedImage result = new BufferedImage(h, w, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = result.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.translate(result.getWidth() / 2.0, result.getHeight() / 2.0);
            g.rotate(Math.toRadians(angle));
            g.translate(-w / 2.0, -h / 2.0);
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return result;
        }
        // 任意角度：计算旋转后的外接矩形
        double radians = Math.toRadians(angle);
        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));
        int newW = (int) Math.floor(w * cos + h * sin);
        int newH = (int) Math.floor(w * sin + h * cos);
        BufferedImage result = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.translate(newW / 2.0, newH / 2.0);
        g.rotate(radians);
        g.translate(-w / 2.0, -h / 2.0);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return result;
    }

    /**
     * 旋转 180 度
     *
     * @param image 源图像
     * @return 旋转后的图像
     */
    private BufferedImage rotate180(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                result.setRGB(w - 1 - x, h - 1 - y, image.getRGB(x, y));
            }
        }
        return result;
    }

    /**
     * 裁剪图像
     *
     * @param image  源图像
     * @param params 参数：x / y / width / height
     * @return 裁剪后的图像
     */
    private BufferedImage crop(BufferedImage image, Map<String, Object> params) {
        int x = toInt(params.get("x"), 0);
        int y = toInt(params.get("y"), 0);
        int w = toInt(params.get("width"), 100);
        int h = toInt(params.get("height"), 100);
        int srcW = image.getWidth();
        int srcH = image.getHeight();
        x = Math.max(0, Math.min(x, srcW));
        y = Math.max(0, Math.min(y, srcH));
        w = Math.min(w, srcW - x);
        h = Math.min(h, srcH - y);
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("裁剪尺寸非法");
        }
        return image.getSubimage(x, y, w, h);
    }

    /**
     * 高斯模糊
     *
     * @param image  源图像
     * @param params 参数：sigma（模糊半径）
     * @return 模糊后的图像
     */
    private BufferedImage blur(BufferedImage image, Map<String, Object> params) {
        int sigma = toInt(params.get("sigma"), 3);
        int radius = Math.max(1, sigma);
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        BufferedImage scaled = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(result, 0, 0, image.getWidth(), image.getHeight(), null);
        g.dispose();
        return scaled;
    }

    /**
     * 翻转图像
     *
     * @param image  源图像
     * @param params 参数：axis（h 水平 / v 垂直，默认 h）
     * @return 翻转后的图像
     */
    private BufferedImage flip(BufferedImage image, Map<String, Object> params) {
        String axis = params.get("axis") != null ? params.get("axis").toString() : "h";
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = "v".equalsIgnoreCase(axis) ? x : w - 1 - x;
                int sy = "v".equalsIgnoreCase(axis) ? h - 1 - y : y;
                result.setRGB(sx, sy, image.getRGB(x, y));
            }
        }
        return result;
    }

    /**
     * 调整亮度
     *
     * @param image  源图像
     * @param params 参数：value（[-255, 255]，正数变亮）
     * @return 调整后的图像
     */
    private BufferedImage brightness(BufferedImage image, Map<String, Object> params) {
        int value = toInt(params.get("value"), 10);
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int a = (rgb >> 24) & 0xFF;
                int r = clamp((((rgb >> 16) & 0xFF) + value));
                int g = clamp((((rgb >> 8) & 0xFF) + value));
                int b = clamp((rgb & 0xFF) + value);
                result.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return result;
    }

    /**
     * 调整对比度
     *
     * @param image  源图像
     * @param params 参数：value（[-100, 100]，正数增强）
     * @return 调整后的图像
     */
    private BufferedImage contrast(BufferedImage image, Map<String, Object> params) {
        int value = toInt(params.get("value"), 10);
        double factor = (259.0 * (value + 255.0)) / (255.0 * (259.0 - value));
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int a = (rgb >> 24) & 0xFF;
                int r = clamp((int) (factor * (((rgb >> 16) & 0xFF) - 128) + 128));
                int g = clamp((int) (factor * (((rgb >> 8) & 0xFF) - 128) + 128));
                int b = clamp((int) (factor * ((rgb & 0xFF) - 128) + 128));
                result.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return result;
    }

    /**
     * 绘制边框
     *
     * @param image  源图像
     * @param params 参数：width（边框宽度）/ color（#RRGGBB 或 r,g,b）
     * @return 带边框的图像
     */
    private BufferedImage border(BufferedImage image, Map<String, Object> params) {
        int width = toInt(params.get("width"), 1);
        width = Math.max(0, width);
        int[] rgb = parseColor(params.get("color") != null ? params.get("color").toString() : "#000000");
        Color color = new Color(rgb[0], rgb[1], rgb[2]);
        int outW = image.getWidth() + width * 2;
        int outH = image.getHeight() + width * 2;
        BufferedImage result = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, outW, outH);
        g.drawImage(image, width, width, null);
        g.dispose();
        return result;
    }

    /**
     * 二值化（阈值化）
     *
     * <p>将图像转为灰度后按阈值二值化，大于阈值取白（255），否则取黑（0）。
     * 支持反向阈值（threshold 为负表示取反）。</p>
     *
     * @param image  源图像
     * @param params 参数：threshold（0~255，默认 128）
     * @return 二值化后的灰度图像
     */
    private BufferedImage binarize(BufferedImage image, Map<String, Object> params) {
        int threshold = toInt(params.get("threshold"), 128);
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage gray = grayscale(image);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = gray.getRGB(x, y);
                int lum = (int) (0.299 * ((rgb >> 16) & 0xFF)
                        + 0.587 * ((rgb >> 8) & 0xFF)
                        + 0.114 * (rgb & 0xFF));
                int val = lum >= threshold ? 255 : 0;
                result.setRGB(x, y, (255 << 24) | (val << 16) | (val << 8) | val);
            }
        }
        return result;
    }

    /**
     * 降噪（中值滤波）
     *
     * <p>对每个像素取邻域中值作为输出，可有效去除椒盐噪声。
     * 邻域半径越大去噪越强、细节损失越多。</p>
     *
     * @param image  源图像
     * @param params 参数：radius（邻域半径，默认 1）
     * @return 降噪后的图像
     */
    private BufferedImage denoise(BufferedImage image, Map<String, Object> params) {
        int radius = Math.max(1, toInt(params.get("radius"), 1));
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] window = new int[(2 * radius + 1) * (2 * radius + 1)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = Math.max(0, Math.min(w - 1, x + dx));
                        int ny = Math.max(0, Math.min(h - 1, y + dy));
                        window[n++] = image.getRGB(nx, ny);
                    }
                }
                result.setRGB(x, y, median(window, n));
            }
        }
        return result;
    }

    /**
     * 腐蚀（形态学操作）
     *
     * <p>对灰度图取邻域最小值，亮区收缩、暗区扩张，用于去除细小白噪点。
     * 该操作在灰度/二值图上等价于 Morphology Erode。</p>
     *
     * @param image  源图像
     * @param params 参数：kernel（核尺寸，默认 3，奇数）
     * @return 腐蚀后的灰度图像
     */
    private BufferedImage erode(BufferedImage image, Map<String, Object> params) {
        return morphology(image, params, true);
    }

    /**
     * 膨胀（形态学操作）
     *
     * <p>对灰度图取邻域最大值，亮区扩张、暗区收缩，用于填补细小空洞。
     * 该操作在灰度/二值图上等价于 Morphology Dilate。</p>
     *
     * @param image  源图像
     * @param params 参数：kernel（核尺寸，默认 3，奇数）
     * @return 膨胀后的灰度图像
     */
    private BufferedImage dilate(BufferedImage image, Map<String, Object> params) {
        return morphology(image, params, false);
    }

    /**
     * 形态学基础操作（腐蚀/膨胀）
     *
     * @param image  源图像
     * @param params 参数：kernel（核尺寸，默认 3，奇数）
     * @param erode  true 腐蚀取最小值，false 膨胀取最大值
     * @return 处理后的灰度图像
     */
    private BufferedImage morphology(BufferedImage image, Map<String, Object> params, boolean erode) {
        int kernel = Math.max(3, toInt(params.get("kernel"), 3));
        if (kernel % 2 == 0) {
            kernel++;
        }
        int half = kernel / 2;
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage gray = grayscale(image);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int best = erode ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                for (int dy = -half; dy <= half; dy++) {
                    for (int dx = -half; dx <= half; dx++) {
                        int nx = Math.max(0, Math.min(w - 1, x + dx));
                        int ny = Math.max(0, Math.min(h - 1, y + dy));
                        int lum = luminance(gray.getRGB(nx, ny));
                        if (erode) {
                            best = Math.min(best, lum);
                        } else {
                            best = Math.max(best, lum);
                        }
                    }
                }
                int val = Math.max(0, Math.min(255, best));
                result.setRGB(x, y, (255 << 24) | (val << 16) | (val << 8) | val);
            }
        }
        return result;
    }

    /**
     * 边缘检测（Sobel 算子）
     *
     * <p>使用 Sobel 算子检测图像边缘，支持水平和垂直方向。
     * 先将图像转为灰度，然后分别应用水平和垂直 Sobel 算子，
     * 最后通过梯度幅值合成边缘图像。</p>
     *
     * @param image  源图像
     * @param params 参数：direction（h 水平 / v 垂直 / both 双向，默认 both）
     * @return 边缘检测后的灰度图像
     */
    private BufferedImage edge(BufferedImage image, Map<String, Object> params) {
        String direction = params.get("direction") != null ? params.get("direction").toString() : "both";
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage gray = grayscale(image);

        // Sobel 算子
        int[] sobelX = {-1, 0, 1, -2, 0, 2, -1, 0, 1}; // 水平方向（检测垂直边缘）
        int[] sobelY = {-1, -2, -1, 0, 0, 0, 1, 2, 1}; // 垂直方向（检测水平边缘）

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int gx = 0, gy = 0;
                // 3x3 卷积
                for (int ky = -1; ky <= 1; ky++) {
                    for (int kx = -1; kx <= 1; kx++) {
                        int lum = luminance(gray.getRGB(x + kx, y + ky));
                        int ki = (ky + 1) * 3 + (kx + 1);
                        if ("v".equalsIgnoreCase(direction)) {
                            gy += sobelY[ki] * lum;
                        } else if ("h".equalsIgnoreCase(direction)) {
                            gx += sobelX[ki] * lum;
                        } else {
                            // both: 双向
                            gx += sobelX[ki] * lum;
                            gy += sobelY[ki] * lum;
                        }
                    }
                }
                int magnitude;
                if ("v".equalsIgnoreCase(direction)) {
                    magnitude = Math.min(255, Math.abs(gy));
                } else if ("h".equalsIgnoreCase(direction)) {
                    magnitude = Math.min(255, Math.abs(gx));
                } else {
                    magnitude = Math.min(255, (int) Math.sqrt(gx * gx + gy * gy));
                }
                result.setRGB(x, y, (255 << 24) | (magnitude << 16) | (magnitude << 8) | magnitude);
            }
        }
        return result;
    }

    /**
     * 计算像素亮度（灰度值）
     *
     * @param rgb ARGB 像素
     * @return 亮度 0~255
     */
    private int luminance(int rgb) {
        return (int) (0.299 * ((rgb >> 16) & 0xFF)
                + 0.587 * ((rgb >> 8) & 0xFF)
                + 0.114 * (rgb & 0xFF));
    }

    /**
     * 计算数组的中值（就地排序）
     *
     * @param values 数组
     * @param length 有效长度
     * @return 中值像素
     */
    private int median(int[] values, int length) {
        int[] copy = new int[length];
        System.arraycopy(values, 0, copy, 0, length);
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    /**
     * 解析颜色
     *
     * @param colorStr 颜色字符串（#RRGGBB 或 r,g,b）
     * @return RGB 三元组
     */
    private int[] parseColor(String colorStr) {
        String s = colorStr.trim();
        if (s.startsWith("#") && s.length() == 7) {
            try {
                return new int[]{
                        Integer.parseInt(s.substring(1, 3), 16),
                        Integer.parseInt(s.substring(3, 5), 16),
                        Integer.parseInt(s.substring(5, 7), 16)
                };
            } catch (NumberFormatException ignored) {
                // 忽略非法颜色，返回黑色
            }
            return new int[]{0, 0, 0};
        }
        String[] parts = s.split(",");
        if (parts.length == 3) {
            try {
                return new int[]{
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim())
                };
            } catch (NumberFormatException ignored) {
                // 忽略非法颜色，返回黑色
            }
        }
        return new int[]{0, 0, 0};
    }

    /**
     * 将值钳制到 [0, 255]
     *
     * @param v 原始值
     * @return 钳制后的值
     */
    private int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * 编码输出
     *
     * @param image  处理后的图像
     * @param params 参数：format（png / jpeg，默认 png）
     * @return 编码后的字节
     */
    private byte[] encode(BufferedImage image, Map<String, Object> params) throws IOException {
        String format = params.get("format") != null ? params.get("format").toString() : "png";
        if ("jpeg".equalsIgnoreCase(format)) {
            BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
            image = rgb;
            format = "jpg";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /**
     * 将参数转为整数
     *
     * @param value      参数值
     * @param defaultVal 默认值
     * @return 整数值
     */
    private int toInt(Object value, int defaultVal) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // 忽略非法参数
            }
        }
        return defaultVal;
    }

    @Override
    /** Name */
    public String name() {
        return "jdk";
    }

    @Override
    /** Available */
    public boolean available() {
        return true;
    }
}