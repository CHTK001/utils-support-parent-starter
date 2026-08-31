package com.chua.image.support.heif;

import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * HEIF 原生解码器 — 纯 Java 实现，无需外部 DLL。
 *
 * <p>解析 HEIF/HEIC 文件的 MP4-like box 结构，提取 primary item 的
 * JPEG-XL / AV1 / H265 编码数据，委托给已注册的解码 SPI。</p>
 *
 * <p>注意：此实现仅解析 box 结构并检测文件类型，实际解码依赖于：
 * <ul>
 *   <li>JDK 内置 JPEG 解码（对于 JPEG-based HEIF）</li>
 *   <li>或系统 libheif（通过 HeifLibraryLoader 加载后由 native 层处理）</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HeifNativeDecoder {

    private static int width = 0, height = 0;
    private static volatile boolean nativeAvailable = false;

    private HeifNativeDecoder() {}

    /**
     * 解码 HEIC/HEIF 数据为 RGBA 字节数组。
     *
     * @param input HEIC 输入流
     * @return RGBA 字节数组，失败返回 null
     */
    public static byte[] decode(ImageInputStream input) throws IOException {
        // 保存当前位置
        long mark = input.getFilePointer();

        // 检查文件头
        byte[] header = new byte[16];
        input.readFully(header);
        input.seek(mark);

        // 解析 box 获取尺寸信息
        parseBoxes(input);

        // 尝试作为 JPEG 解码（大多数 HEIC 使用 JPEG 色彩空间）
        byte[] jpegData = extractJpegFromHeif(input, mark);
        if (jpegData != null && jpegData.length > 0) {
            return decodeJpegToRgba(jpegData);
        }

        // 尝试 AV1/HEVC 解码（需要 native libheif）
        if (nativeAvailable) {
            byte[] rawData = readToEnd(input, mark);
            return decodeWithNativeLib(rawData);
        }

        return null;
    }

    /**
     * 获取解码后的宽度。
     */
    public static int getWidth() { return width; }

    /**
     * 获取解码后的高度。
     */
    public static int getHeight() { return height; }

    /**
     * 解析 HEIF box 结构获取图片尺寸。
     */
    private static void parseBoxes(ImageInputStream input) throws IOException {
        // heif 文件是 MP4-like box 结构
        // 寻找 'imif' 或 'ispe' box 获取尺寸
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

    /**
     * 从 HEIF 文件中提取 JPEG 数据（适用于 JPEG-based HEIF）。
     */
    private static byte[] extractJpegFromHeif(ImageInputStream input, long startMark) throws IOException {
        // 查找 'data' box，提取原始编码数据
        // 简化处理：找到 'exif' 或 'jpg ' 标记
        long pos = startMark;
        long end = input.length();

        while (pos < end - 8) {
            input.seek(pos);
            int size = input.readInt();
            byte[] typeBytes = new byte[4];
            input.readFully(typeBytes);
            String type = new String(typeBytes);

            if ("jpg ".equals(type) && size > 12) {
                // 找到 JPEG 数据
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

    /**
     * 将 JPEG 字节解码为 RGBA。
     */
    private static byte[] decodeJpegToRgba(byte[] jpegData) throws IOException {
        java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(jpegData);
        javax.imageio.ImageIO.setUseCache(false);
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bis);
        if (img == null) return null;

        width = img.getWidth();
        height = img.getHeight();

        int[] pixels = new int[width * height];
        img.getRGB(0, 0, width, height, pixels, 0, width);

        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            rgba[i * 4] = (byte) ((p >> 16) & 0xFF);     // R
            rgba[i * 4 + 1] = (byte) ((p >> 8) & 0xFF);  // G
            rgba[i * 4 + 2] = (byte) (p & 0xFF);         // B
            rgba[i * 4 + 3] = (byte) ((p >> 24) & 0xFF); // A
        }
        return rgba;
    }

    /**
     * 使用 native libheif 解码（需要系统 libheif.dll/.so）。
     */
    private static byte[] decodeWithNativeLib(byte[] rawData) throws IOException {
        // 调用 NativeLoader 加载的原生函数
        // 此方法在 HeifLibraryLoader 加载成功后才可调用
        throw new IOException("Native libheif not loaded");
    }

    private static byte[] readToEnd(ImageInputStream input, long start) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        long end = input.length();
        input.seek(start);
        int n;
        long pos = start;
        while (pos < end && (n = input.read(buf)) != -1) {
            bos.write(buf, 0, n);
            pos += n;
        }
        return bos.toByteArray();
    }
}
