package com.chua.deeplearning.support.utils;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 图片裁剪工具（基于检测框）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ImageCropUtils {

    private ImageCropUtils() {
    }

    /**
     * 按像素框裁剪。
     *
     * @param imageData 原图
     * @param x         左
     * @param y         上
     * @param width     宽
     * @param height    高
     * @return 裁剪图 PNG 字节
     */
    public static byte[] crop(byte[] imageData, int x, int y, int width, int height) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                throw new IllegalArgumentException("无法解码图片");
            }
            int imgW = image.getWidth();
            int imgH = image.getHeight();
            int left = clamp(x, 0, imgW - 1);
            int top = clamp(y, 0, imgH - 1);
            int w = Math.max(1, Math.min(width, imgW - left));
            int h = Math.max(1, Math.min(height, imgH - top));
            BufferedImage sub = image.getSubimage(left, top, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(sub, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("图片裁剪失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按 {@link PredictRectangle} 裁剪。
     * <p>宽高 &lt;= 1 时按归一化坐标处理，否则按像素。</p>
     *
     * @param imageData 原图
     * @param box       框
     * @return 裁剪图
     */
    public static byte[] crop(byte[] imageData, PredictRectangle box) {
        if (box == null) {
            return imageData;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                throw new IllegalArgumentException("无法解码图片");
            }
            return cropByNormalizedOrPixel(image, box.x(), box.y(), box.width(), box.height());
        } catch (IOException e) {
            throw new IllegalStateException("图片裁剪失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按 {@link DetectionInfo} 裁剪。
     *
     * @param imageData 原图
     * @param info      检测结果
     * @return 裁剪图
     */
    public static byte[] crop(byte[] imageData, DetectionInfo info) {
        if (info == null) {
            return imageData;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                throw new IllegalArgumentException("无法解码图片");
            }
            return cropByNormalizedOrPixel(image, info.x(), info.y(), info.width(), info.height());
        } catch (IOException e) {
            throw new IllegalStateException("图片裁剪失败: " + e.getMessage(), e);
        }
    }

    private static byte[] cropByNormalizedOrPixel(BufferedImage image,
                                                  float x, float y, float width, float height) throws IOException {
        int imgW = image.getWidth();
        int imgH = image.getHeight();
        boolean normalized = width <= 1.5f && height <= 1.5f && x <= 1.5f && y <= 1.5f;
        int left;
        int top;
        int w;
        int h;
        if (normalized) {
            left = clamp(Math.round(x * imgW), 0, imgW - 1);
            top = clamp(Math.round(y * imgH), 0, imgH - 1);
            w = Math.max(1, Math.round(width * imgW));
            h = Math.max(1, Math.round(height * imgH));
        } else {
            left = clamp(Math.round(x), 0, imgW - 1);
            top = clamp(Math.round(y), 0, imgH - 1);
            w = Math.max(1, Math.round(width));
            h = Math.max(1, Math.round(height));
        }
        w = Math.min(w, imgW - left);
        h = Math.min(h, imgH - top);
        BufferedImage sub = image.getSubimage(left, top, Math.max(1, w), Math.max(1, h));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(sub, "png", out);
        return out.toByteArray();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
