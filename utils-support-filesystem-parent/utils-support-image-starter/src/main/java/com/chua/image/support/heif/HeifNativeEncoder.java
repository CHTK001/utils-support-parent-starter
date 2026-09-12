package com.chua.image.support.heif;

import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
   * HEIF 原生编码器 — 将 缓冲镜像 编码为 HEIC（JPEG-基础 HEIF）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HeifNativeEncoder {

    /**
     * heifNAT编码器。
     */
    private HeifNativeEncoder() {}

    /**
     * encode。
     * @param image 镜像
     * @param output 输出
     */
    public static void encode(BufferedImage image, ImageOutputStream output) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] jpegData = toJpegBytes(image, 0.92f);
        if (jpegData == null || jpegData.length == 0) {
            throw new IOException("Failed to encode image as JPEG for HEIF");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // ftyp body (writeBox adds size+type wrapper)
        ByteArrayOutputStream ftypBody = new ByteArrayOutputStream();
        safeWrite(ftypBody, "heic".getBytes());
        safeWrite(ftypBody, new byte[]{0, 0, 0, 1});
        safeWrite(ftypBody, "heic".getBytes());
        safeWrite(ftypBody, "mif1".getBytes());
        safeWrite(ftypBody, "pyif".getBytes());
        writeBox(bos, "ftyp", ftypBody.toByteArray());
 // meta 容器
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        writeBox(meta, "hinf", buildHinfBox(w, h));
        writeBox(meta, "idat", jpegData);
        writeBox(meta, "ispe", buildIspeBox(w, h));
        writeBox(meta, "pitm", new byte[]{0, 0, 0, 0, 0, 0, 0, 1});
        writeBox(meta, "imif", new byte[]{0, 0, 0, 1});
        writeBox(bos, "meta", meta.toByteArray());
        output.write(bos.toByteArray());
    }

    /**
     * 写入box。
     * @param out 出
     * @param type 类型
     * @param data 数据
     */
    private static void writeBox(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        int size = 8 + data.length;
        out.write(intToBytes(size));
        out.write(type.getBytes("ASCII"));
        out.write(data, 0, data.length);
    }

    /**
     * int转为bytes。
     * @param value 值
     * @return int转为bytes的结果
     */
    private static byte[] intToBytes(int value) {
        return new byte[]{
            (byte)((value >> 24) & 0xFF),
            (byte)((value >> 16) & 0xFF),
            (byte)((value >> 8) & 0xFF),
            (byte)(value & 0xFF)
        };
    }

    /**
     * 构建hinfbox。
     * @param w w
     * @param h h
     * @return 构建hinfbox的结果
     */
    private static byte[] buildHinfBox(int w, int h) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        safeWrite(bos, new byte[]{0, 0, 0, 0});
        safeWrite(bos, intToBytes(w));
        safeWrite(bos, intToBytes(h));
        safeWrite(bos, new byte[8]);
        safeWrite(bos, new byte[]{0, 0, 0, 1});
        return bos.toByteArray();
    }

    /**
     * 构建ispebox。
     * @param w w
     * @param h h
     * @return 构建ispebox的结果
     */
    private static byte[] buildIspeBox(int w, int h) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        safeWrite(bos, new byte[]{0, 0, 0, 0});
        safeWrite(bos, intToBytes(w));
        safeWrite(bos, intToBytes(h));
        safeWrite(bos, new byte[2]);
        return bos.toByteArray();
    }

    /**
     * safe写入。
     * @param out 出
     * @param data 数据
     */
    private static void safeWrite(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }

    /**
     * 转为jpegbytes。
     * @param image 镜像
     * @param quality quality
     * @return 转为jpegbytes的结果
     */
    private static byte[] toJpegBytes(BufferedImage image, float quality) throws IOException {
        // Convert to RGB for JPEG encoding (JPEG does not support alpha or indexed color)
        BufferedImage rgb;
        if (image.getType() == BufferedImage.TYPE_INT_RGB
                || image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            rgb = image;
        } else {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(image, 0, 0, null);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer =
                javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next();
        try {
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(javax.imageio.ImageIO.createImageOutputStream(bos));
            writer.write(null, new javax.imageio.IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }
}
