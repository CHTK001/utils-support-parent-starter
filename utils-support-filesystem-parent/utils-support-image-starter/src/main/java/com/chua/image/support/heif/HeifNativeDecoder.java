package com.chua.image.support.heif;

import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * HEIF 原生解码器 — 解析 box 结构，提取 JPEG 数据后由 JDK 解码。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HeifNativeDecoder {

    private static int width = 0, height = 0;

    private HeifNativeDecoder() {}

    /**
     * 解码 HEIC/HEIF 数据为 RGBA 字节数组。
     */
    public static byte[] decode(ImageInputStream input) throws IOException {
        byte[] header = new byte[16];
        input.readFully(header);
        input.seek(0);

        if (header.length < 12 || !"ftyp".equals(new String(header, 4, 4))) {
            return null;
        }
        String brand = new String(header, 8, 4);
        boolean isHeif = brand.startsWith("heic") || brand.startsWith("heix")
                || brand.startsWith("heim") || brand.startsWith("hevc")
                || brand.startsWith("mif1") || brand.startsWith("msf1");
        if (!isHeif) return null;

        parseBoxes(input);

        byte[] jpegData = extractJpegFromHeif(input, 0);
        if (jpegData != null && jpegData.length > 0) {
            return decodeJpegToRgba(jpegData);
        }
        return null;
    }

    public static int getWidth() { return width; }
    public static int getHeight() { return height; }

    private static void parseBoxes(ImageInputStream input) throws IOException {
        long end = input.length();
        long pos = 0;
        while (pos < end - 8) {
            input.seek(pos);
            int size = input.readInt();
            byte[] typeBytes = new byte[4];
            input.readFully(typeBytes);
            String type = new String(typeBytes);
            if ("ispe".equals(type) && size >= 20) {
                input.seek(pos + 8);
                width = input.readInt();
                height = input.readInt();
                return;
            }
            if (size <= 0) break;
            pos += size;
        }
    }

    private static byte[] extractJpegFromHeif(ImageInputStream input, long start) throws IOException {
        long end = input.length();
        long pos = start;
        while (pos < end - 8) {
            input.seek(pos);
            int size = input.readInt();
            byte[] typeBytes = new byte[4];
            input.readFully(typeBytes);
            String type = new String(typeBytes);
            if ("jpg ".equals(type) && size > 12) {
                int dataStart = (int) (pos + 8);
                int dataLen = size - 8;
                byte[] jpegData = new byte[dataLen];
                input.seek(dataStart);
                input.readFully(jpegData);
                return jpegData;
            }
            if (size <= 0) break;
            pos += size;
        }
        return null;
    }

    private static byte[] decodeJpegToRgba(byte[] jpegData) throws IOException {
        java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(jpegData);
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bis);
        if (img == null) return null;

        width = img.getWidth();
        height = img.getHeight();

        int[] pixels = new int[width * height];
        img.getRGB(0, 0, width, height, pixels, 0, width);

        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            rgba[i * 4] = (byte) ((p >> 16) & 0xFF);
            rgba[i * 4 + 1] = (byte) ((p >> 8) & 0xFF);
            rgba[i * 4 + 2] = (byte) (p & 0xFF);
            rgba[i * 4 + 3] = (byte) 0xFF;
        }
        return rgba;
    }
}
