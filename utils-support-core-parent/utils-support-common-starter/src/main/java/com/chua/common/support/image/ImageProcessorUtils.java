package com.chua.common.support.image;

/**
 * 图像处理器工具类，提取各 {@link ImageProcessor} 实现中的公共方法。
 *
 * <p>本类统一管理以下重复逻辑，避免 JdkImageProcessor / OpenCVImageProcessor / Filter 等各自维护副本：
 * <ul>
 *   <li>{@link #toInt(Object, int)} — 参数值转整数（支持 Number / String 类型）</li>
 *   <li>{@link #clamp(int)} — 将值钳制到 [0, 255] 范围</li>
 *   <li>{@link #luminance(int)} — 计算 ARGB 像素的 ITU-R BT.601 亮度值</li>
 *   <li>{@link #parseColor(String)} — 解析颜色字符串（#RRGGBB 或 r,g,b）为 RGB 三元组</li>
 *   <li>{@link #SOBEL_X} / {@link #SOBEL_Y} — Sobel 边缘检测卷积核常量</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 参数转整数
 * int width = ImageProcessorUtils.toInt(params.get("width"), 200);
 *
 * // 颜色解析
 * int[] rgb = ImageProcessorUtils.parseColor("#FF8800");
 *
 * // 像素亮度
 * int gray = ImageProcessorUtils.luminance(pixel);
 *
 * // 值钳制
 * int clamped = ImageProcessorUtils.clamp(value);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see ImageProcessor
 * @see ImageProcessors
 */
public final class ImageProcessorUtils {

    /**
     * Sobel 水平方向卷积核（检测垂直边缘）
     *
     * <p>3×3 矩阵按行优先存储：
     * <pre>
     * -1  0  1
     * -2  0  2
     * -1  0  1
     * </pre>
     */
    public static final int[] SOBEL_X = {-1, 0, 1, -2, 0, 2, -1, 0, 1};

    /**
     * Sobel 垂直方向卷积核（检测水平边缘）
     *
     * <p>3×3 矩阵按行优先存储：
     * <pre>
     * -1  -2  -1
     *  0   0   0
     *  1   2   1
     * </pre>
     */
    public static final int[] SOBEL_Y = {-1, -2, -1, 0, 0, 0, 1, 2, 1};

    /**
     * 私有构造方法，防止实例化
     */
    private ImageProcessorUtils() {
    }

    /**
     * 将参数值转为整数
     *
     * <p>支持以下类型：
     * <ul>
     *   <li>{@link Number} — 直接调用 {@code intValue()}</li>
     *   <li>{@link String} — 尝试 {@code Integer.parseInt()}，失败返回默认值</li>
     *   <li>其他类型 — 返回默认值</li>
     * </ul>
     *
     * <p>此方法统一了 JdkImageProcessor 和 OpenCVImageProcessor 中重复的 {@code toInt} 实现，
     * 同时扩展了 {@link com.chua.common.support.utils.NumberUtils#toInt(String, int)} 的功能，
     * 支持 Object 类型参数（Map 参数值通常为 Object）。</p>
     *
     * @param value      参数值，可能为 null / Number / String
     * @param defaultVal 解析失败时的默认值
     * @return 解析后的整数值
     */
    public static int toInt(Object value, int defaultVal) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // 忽略非法参数，返回默认值
            }
        }
        return defaultVal;
    }

    /**
     * 将值钳制到 [0, 255] 范围
     *
     * <p>常用于图像像素分量（R/G/B/A）的溢出保护，确保值不超出字节范围。
     * 等价于 {@code Math.max(0, Math.min(255, v))}。</p>
     *
     * <p>此方法统一了 JdkImageProcessor 和 8 个 ImageFilter 中的重复 {@code clamp} 实现。
     * 与 {@link com.chua.common.support.utils.BufferedImageUtils#clamp(float)} 互补，
     * 本方法接受 int 参数，避免调用处额外的类型转换。</p>
     *
     * @param v 原始值，可能超出 [0, 255]
     * @return 钳制后的值，保证在 [0, 255] 范围内
     */
    public static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * 计算 ARGB 像素的亮度（灰度值）
     *
     * <p>使用 ITU-R BT.601 标准权重系数：
     * <pre>Y = 0.299 × R + 0.587 × G + 0.114 × B</pre>
     *
     * <p>此方法统一了 JdkImageProcessor、ClayStyleImageFilter、GhibliStudioImageFilter
     * 中的重复亮度计算逻辑。</p>
     *
     * @param rgb ARGB 像素值（格式：0xAARRGGBB）
     * @return 亮度值，范围 [0, 255]
     */
    public static int luminance(int rgb) {
        return (int) (0.299 * ((rgb >> 16) & 0xFF)
                + 0.587 * ((rgb >> 8) & 0xFF)
                + 0.114 * (rgb & 0xFF));
    }

    /**
     * 解析颜色字符串为 RGB 三元组
     *
     * <p>支持以下格式：
     * <ul>
     *   <li>{@code #RRGGBB} — 十六进制颜色（如 {@code #FF8800}）</li>
     *   <li>{@code r,g,b} — 十进制逗号分隔（如 {@code 255,136,0}）</li>
     * </ul>
     *
     * <p>解析失败时返回 {@code {0, 0, 0}}（黑色）。</p>
     *
     * <p>此方法统一了 JdkImageProcessor 和 OpenCVImageProcessor 中重复的颜色解析逻辑。
     * 注意：OpenCV 使用 BGR 顺序，调用处需自行反转数组元素顺序。</p>
     *
     * @param colorStr 颜色字符串，支持 #RRGGBB 或 r,g,b 格式
     * @return RGB 三元组，索引 0=R、1=G、2=B
     */
    public static int[] parseColor(String colorStr) {
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
}