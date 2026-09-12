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

    private static int width = 0, height = 0; // width

    /**
    * heifNAT解码器。
     */
    private HeifNativeDecoder() {}

    /**
    * 解码 HEIC/HEIF 数据为 RGBA 字节数组。
    * @param input 输入
    * @return decode的结果
     */
    public static byte[] decode(ImageInputStream input) throws IOException {
        // 读取并验证文件头
        byte[] header = new byte[16];
        input.readFully(header);

        // 验证 ftyp box
        if (header.length < 12 || !"ftyp".equals(new String(header, 4, 4))) {
            return null;
        }
        String brand = new String(header, 8, 4);
        boolean isHeif = brand.startsWith("heic") || brand.startsWith("heix")
                || brand.startsWith("heim") || brand.startsWith("hevc")
                || brand.startsWith("mif1") || brand.startsWith("msf1");
        if (!isHeif) {
            return null;
        }

        // 重置到文件开头
        input.seek(0);

        // 解析 box 获取尺寸
        parseBoxes(input);

        // 再次重置，提取 JPEG
        input.seek(0);
        byte[] jpegData = extractJpegFromHeif(input, 0);
        if (jpegData != null && jpegData.length > 0) {
            return decodeJpegToRgba(jpegData);
        }
        return null;
    }

    /**
    * 获取width。
    * @return 获取width的结果
     */
    public static int getWidth() { return width; }
    /**
    * 获取height。
    * @return 获取height的结果
     */
    public static int getHeight() { return height; }

    /**
    * 解析boxes。
    * @param input 输入
     */
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
            if (size <= 0) {
                break;
            }
            pos += size;
        }
    }

    /**
    * extractjpeg从heif。
    * @param input 输入
    * @param start 启动
    * @return extractjpeg从heif的结果
     */
    private static byte[] extractJpegFromHeif(ImageInputStream input, long start) throws IOException {
        long end = input.length();
        long pos = start;
        while (pos < end - 8) {
            input.seek(pos);
            int size = input.readInt();
            byte[] typeBytes = new byte[4];
            input.readFully(typeBytes);
            String type = new String(typeBytes);
            if ("idat".equals(type) && size > 8) {
                int dataStart = (int) (pos + 8);
                int dataLen = size - 8;
                byte[] jpegData = new byte[dataLen];
                input.seek(dataStart);
                input.readFully(jpegData);
                return jpegData;
            }
            // 递归进入 meta 容器查找 idat
            if ("meta".equals(type) && size > 8) {
                long metaEnd = pos + size;
                long innerPos = pos + 8;
                while (innerPos < metaEnd - 8) {
                    input.seek(innerPos);
                    int innerSize = input.readInt();
                    byte[] innerTypeBytes = new byte[4];
                    input.readFully(innerTypeBytes);
                    String innerType = new String(innerTypeBytes);
                    if ("idat".equals(innerType) && innerSize > 8) {
                        input.seek(innerPos + 8);
                        byte[] jpegData = new byte[innerSize - 8];
                        input.readFully(jpegData);
                        return jpegData;
                    }
                    if (innerSize <= 0) {
                        break;
                    }
                    innerPos = innerPos + innerSize;
                }
                pos = metaEnd;
                continue;
            }
            if (size <= 0) {
                break;
            }
            pos += size;
        }
        return null;
    }

    /**
    * decodejpeg转为rgba。
    * @param jpegData jpeg数据
    * @return decodejpeg转为rgba的结果
     */
    private static byte[] decodeJpegToRgba(byte[] jpegData) throws IOException {
        java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(jpegData);
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bis);
        if (img == null) {
            return null;
        }

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
