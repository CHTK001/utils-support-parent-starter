package com.chua.image.support.heif;

import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * HEIF 原生编码器 — 纯 Java 实现。
 *
 * <p>将 BufferedImage 编码为 HEIC/HEIF 格式。
 * 当前实现仅支持 JPEG-based HEIF（最常用格式）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HeifNativeEncoder {

    private HeifNativeEncoder() {}

    /**
     * 将 BufferedImage 编码为 HEIC 并写入输出流。
     *
     * @param image 源图像（支持 RGB/RGBA）
     * @param output 目标输出流
     */
    public static void encode(BufferedImage image, ImageOutputStream output) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();

        // 步骤1：将 BufferedImage 转为 JPEG 字节（HEIF 内部通常用 JPEG 存储）
        byte[] jpegData = toJpegBytes(image, 0.92f);
        if (jpegData == null || jpegData.length == 0) {
            throw new IOException("Failed to encode image as JPEG for HEIF");
        }

        // 步骤2：构建 HEIF box 结构
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        // ftyp box
        writeBox(bos, "ftyp", buildFtypBox(jpegData.length));
        // meta box (container)
        ByteArrayOutputStream metaContents = new ByteArrayOutputStream();
        // hinf box (header info)
        writeBox(metaContents, "hinf", buildHinfBox(w, h));
        // idat box (image data - JPEG)
        writeBox(metaContents, "idat", jpegData);
        // ispe box (image spatial extent)
        writeBox(metaContents, "ispe", buildIspeBox(w, h));
        // pitm box (primary item)
        writeBox(metaContents, "pitm", buildPitmBox());
        // imif box
        writeBox(metaContents, "imif", new byte[]{0, 0, 0, 1}); // 1 item

        writeBox(bos, "meta", metaContents.toByteArray());

        byte[] heifData = bos.toByteArray();
        output.write(heifData);
    }

    private static void writeBox(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        int size = 8 + data.length;
        out.write(intToBytes(size));
        out.write(type.getBytes());
        out.write(data);
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
            (byte)((value >> 24) & 0xFF),
            (byte)((value >> 16) & 0xFF),
            (byte)((value >> 8) & 0xFF),
            (byte)(value & 0xFF)
        };
    }

    private static byte[] buildFtypBox(int idatSize) {
        // ftyp: brand=heic, minor=0, compatible_brands=[heic, mif1]
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{0, 0, 0, 0}); // size placeholder
        bos.write("ftyp".getBytes());
        bos.write("heic".getBytes()); // major brand
        bos.write(new byte[]{0, 0, 0, 1}); // minor version
        bos.write("heic".getBytes()); // compatible brand 1
        bos.write("mif1".getBytes()); // compatible brand 2
        bos.write("pyif".getBytes()); // compatible brand 3
        byte[] body = bos.toByteArray();
        int totalSize = 8 + body.length;
        byte[] result = new byte[4 + body.length];
        intToBytes(totalSize, result, 0);
        System.arraycopy(body, 0, result, 4, body.length);
        return result;
    }

    private static byte[] buildHinfBox(int w, int h) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{0, 0, 0, 0}); // version/flags
        bos.write(intToBytes(w));
        bos.write(intToBytes(h));
        bos.write(new byte[8]); // transform matrix (identity)
        bos.write(new byte[]{0, 0, 0, 1}); // item count
        return bos.toByteArray();
    }

    private static byte[] buildIspeBox(int w, int h) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{0, 0, 0, 0});
        bos.write(intToBytes(w));
        bos.write(intToBytes(h));
        bos.write(new byte[2]); // reserved
        return bos.toByteArray();
    }

    private static byte[] buildPitmBox() {
        // pitm: version=0, flags=0, item_ID=1
        return new byte[]{0, 0, 0, 0, 0, 0, 0, 1};
    }

    private static byte[] toJpegBytes(BufferedImage image, float quality) throws IOException {
        // 转换颜色空间
        BufferedImage rgbImage;
        if (image.getType() == BufferedImage.TYPE_INT_RGB ||
            image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            rgbImage = image;
        } else {
            rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgbImage.getGraphics().drawImage(image, 0, 0, null);
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = javax.imageio.ImageIO
                .getImageWritersByFormatName("jpg").next();
        try {
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(javax.imageio.ImageIO.createImageOutputStream(bos));
            writer.write(null, new javax.imageio.IIOImage(rgbImage, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    private static void intToBytes(int value, byte[] bytes, int offset) {
        bytes[offset] = (byte)((value >> 24) & 0xFF);
        bytes[offset + 1] = (byte)((value >> 16) & 0xFF);
        bytes[offset + 2] = (byte)((value >> 8) & 0xFF);
        bytes[offset + 3] = (byte)(value & 0xFF);
    }

    private static byte[] intToBytes(int value) {
        byte[] b = new byte[4];
        intToBytes(value, b, 0);
        return b;
    }
}
