package com.chua.remote.agent;

import com.chua.common.support.image.ImageProcessors;
import com.chua.common.support.image.BufferedImageUtils;
import com.chua.remote.protocol.capability.CodecProfile;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * Native 编码器。
 *
 * <p>使用 {@link ImageProcessors} 流畅 API 进行图像缩放和格式编码。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class NativeEncoder {

    /** 编码能力 */
    private final CodecProfile capability;

    public NativeEncoder(CodecProfile capability) {
        this.capability = capability;
    }

    /**
     * 编码 BufferedImage。
     *
     * @param image 原始图像
     * @return 编码后的字节数组（JPEG 格式）
     */
    public byte[] encode(BufferedImage image) {
        if (image == null) {
            return new byte[0];
        }
        try {
            byte[] rawBytes = BufferedImageUtils.toBufferedImageArray(image, "png");
            // 使用 ImageProcessors 流畅 API 进行缩放和格式转换
            int targetWidth = capability.getMaxWidth();
            int targetHeight = capability.getMaxHeight();

            ImageProcessors.FluentProcessor fp = ImageProcessors.from(rawBytes)
                    .resize(targetWidth, targetHeight);

            if ("jpeg".equalsIgnoreCase(getPrimaryEncoding())) {
                fp = fp.format("jpeg");
            } else if ("webp".equalsIgnoreCase(getPrimaryEncoding())) {
                fp = fp.format("webp");
            } else if ("png".equalsIgnoreCase(getPrimaryEncoding())) {
                fp = fp.format("png");
            }

            return fp.toBytes();
        } catch (Exception e) {
            log.error("Native 编码失败", e);
            return new byte[0];
        }
    }

    /**
     * 编码像素数据（字节数组形式的 RGB 数据）。
     *
     * @param pixels   像素数据
     * @param width    宽度
     * @param height   高度
     * @return 编码后的字节数组
     */
    public byte[] encodePixels(byte[] pixels, int width, int height) {
        // 将像素数组转换为 BufferedImage 再编码
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int index = 0;
        for (int y = 0; y < height && index < pixels.length; y++) {
            for (int x = 0; x < width && index < pixels.length; x++) {
                int rgb = pixels[index++] & 0xFF;
                image.setRGB(x, y, (255 << 24) | (rgb << 16) | (rgb << 8) | rgb);
            }
        }
        return encode(image);
    }

    /**
     * 编码 BufferedImage 为指定格式。
     *
     * @param image  原始图像
     * @param format 输出格式（jpeg/png/webp）
     * @return 编码后的字节数组
     */
    public byte[] encode(BufferedImage image, String format) {
        if (image == null) {
            return new byte[0];
        }
        try {
            byte[] rawBytes = BufferedImageUtils.toBufferedImageArray(image, "png");
            return ImageProcessors.from(rawBytes)
                    .resize(capability.getMaxWidth(), capability.getMaxHeight())
                    .format(format)
                    .toBytes();
        } catch (Exception e) {
            log.error("Native 编码失败: format={}", format, e);
            return new byte[0];
        }
    }

    /**
     * 生成缩略图。
     *
     * @param data 原始数据
     * @return 缩略图数据
     */
    public byte[] generateThumbnail(byte[] data) {
        if (!capability.isThumbnailSupported()) {
            return data;
        }
        try {
            return ImageProcessors.from(data)
                    .resize(capability.getMaxWidth() / 4, capability.getMaxHeight() / 4)
                    .format("jpeg")
                    .toBytes();
        } catch (Exception e) {
            log.error("缩略图生成失败", e);
            return new byte[0];
        }
    }

    /**
     * 缩放图片。
     *
     * @param data   原始数据
     * @param width  目标宽度
     * @param height 目标高度
     * @return 缩放后的数据
     */
    public byte[] scale(byte[] data, int width, int height) {
        if (!capability.isScalingSupported()) {
            return data;
        }
        try {
            return ImageProcessors.from(data)
                    .resize(width, height)
                    .format("jpeg")
                    .toBytes();
        } catch (Exception e) {
            log.error("缩放失败", e);
            return new byte[0];
        }
    }

    /**
     * 获取主编码格式。
     */
    private String getPrimaryEncoding() {
        List<String> encodings = capability.getEncodings();
        if (encodings != null && !encodings.isEmpty()) {
            return encodings.get(0);
        }
        return "jpeg";
    }

    /**
     * 关闭编码器，释放资源。
     */
    public void close() {
        log.info("Native 编码器已关闭");
    }
}
