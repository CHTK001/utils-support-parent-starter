package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.utils.ImageUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Base64;

/**
 * SafeTensor 图像工具类。
 * <p>
 * 将多种输入类型（BufferedImage、byte[]、File、路径字符串、URL、InputStream）统一转为 Base64 PNG。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class SafeTensorImageUtils {

    private SafeTensorImageUtils() {}

    /**
     * 将输入图像转为 Base64 PNG 字符串。
     *
     * @param input 图像输入
     * @return base64 编码字符串
     */
    static String toBase64(Object input) {
        if (input instanceof String text) {
            String normalized = normalizeBase64(text);
            if (normalized != null) {
                return normalized;
            }
        }
        try {
            BufferedImage bi = toBufferedImage(input);
            return Base64.getEncoder().encodeToString(ImageUtils.encode(ImageUtils.toMat(bi)));
        } catch (Exception e) {
            throw new RuntimeException("图像编码失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将输入转为 BufferedImage。
     *
     * @param input 输入
     * @return BufferedImage
     * @throws IOException IO 异常
     */
    static BufferedImage toBufferedImage(Object input) throws IOException {
        if (input instanceof BufferedImage bi) {
            return bi;
        }
        if (input instanceof byte[] bytes) {
            return ImageUtils.toBufferedImage(bytes);
        }
        if (input instanceof File file) {
            return ImageIO.read(file);
        }
        if (input instanceof InputStream is) {
            return ImageIO.read(is);
        }
        if (input instanceof String path) {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return ImageIO.read(new URL(path));
            }
            File file = new File(path);
            if (file.exists()) {
                return ImageIO.read(file);
            }
            throw new IllegalArgumentException("字符串既不是可访问路径/URL，也不是有效 base64 图片");
        }
        throw new IllegalArgumentException("不支持的图像输入类型: " + input.getClass().getName());
    }

    /**
     * 检查并规范化 Base64 字符串。
     *
     * @param text 输入字符串
     * @return 规范化后的 base64，如果非 base64 则返回 null
     */
    private static String normalizeBase64(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return null;
        }
        File file = new File(text);
        if (file.exists()) {
            return null;
        }
        String value = text;
        int comma = value.indexOf(',');
        if (value.startsWith("data:image/") && comma > 0) {
            value = value.substring(comma + 1);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (ImageIO.read(new java.io.ByteArrayInputStream(bytes)) != null) {
                return value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
