package com.chua.image.support.compression.png;

import com.chua.common.support.image.compression.DeflateEngine;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * PNG 无损编码器 / 优化器。
 *
 * <p>直接按 PNG 规范拼装 chunk，把「像素 → 扫描线 → 逐行滤波 → deflate」这条链路
 * 完整暴露出来，从而绕开 ImageIO 通用写出器无法控制的三个优化点：
 * <ol>
 *   <li><b>逐行过滤器策略</b> —— 通用写出器固定用 Paeth，这里可按图选型甚至逐行选型</li>
 *   <li><b>deflate 参数搜索</b> —— 可指定压缩级别与多轮迭代，取多轮中的最小结果</li>
 *   <li><b>元数据块剥离</b> —— 默认只写 IHDR/PLTE/tRNS/IDAT/IEND，
 *       这是 oxipng / ZopfliPNG / pngquant 共同带来的 3%~15% 收益</li>
 * </ol>
 *
 * <h3>支持的输出色彩类型</h3>
 * <ul>
 *   <li><b>3 索引色</b>（bitDepth 8 + PLTE + 可选 tRNS）—— 有损量化的落点</li>
 *   <li><b>2 真彩色</b>（bitDepth 8，RGB）—— 无损 L0 档的落点</li>
 *   <li><b>6 真彩色带 Alpha</b>（bitDepth 8，RGBA）—— 无损 L0 档的落点</li>
 *   <li><b>0 灰度</b> / <b>4 灰度带 Alpha</b> —— 检测到无彩色信息时无损降级</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class PngOptimizer {

    /**
     * PNG 文件签名
     */
    private static final byte[] SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };

    /**
     * 色彩类型：灰度
     */
    public static final int COLOR_TYPE_GRAY = 0;

    /**
     * 色彩类型：真彩色 RGB
     */
    public static final int COLOR_TYPE_RGB = 2;

    /**
     * 色彩类型：索引色
     */
    public static final int COLOR_TYPE_INDEXED = 3;

    /**
     * 色彩类型：灰度带 Alpha
     */
    public static final int COLOR_TYPE_GRAY_ALPHA = 4;

    /**
     * 色彩类型：真彩色 RGBA
     */
    public static final int COLOR_TYPE_RGBA = 6;

    /**
     * IDAT 块之间的最大字节数，避免单块超过 2^31
     */
    private static final int IDAT_CHUNK_SIZE = 1 << 20;

    /**
     * 工具类，禁止实例化
     */
    private PngOptimizer() {
    }

    /**
     * 以索引色写出 PNG。
     *
     * @param indices  索引数据，长度为 {@code width * height}
     * @param palette  调色板
     * @param engine   deflate 引擎
     * @param profile  deflate 压缩档位
     * @param strategy 过滤器选择策略
     * @return 完整的 PNG 文件字节
     * @throws IOException 当写出失败时
     * @throws Exception  当 deflate 引擎执行失败时
     */
    public static byte[] writeIndexed(byte[] indices, int width, int height, IndexColorModel palette,
                                      DeflateEngine engine, DeflateEngine.DeflateProfile profile,
                                      PngFilterOptimizer.Strategy strategy) throws IOException, Exception {
        byte[] scanlines = new byte[height * (width + 1)];
        for (int row = 0; row < height; row++) {
            int target = row * (width + 1) + 1;
            System.arraycopy(indices, row * width, scanlines, target, width);
        }
        byte[] filtered = PngFilterOptimizer.filter(scanlines, width, height, 1, strategy, 4096);
        byte[] idat = engine.deflate(filtered, profile);

        ByteArrayOutputStream out = new ByteArrayOutputStream(idat.length + 1024);
        out.write(SIGNATURE);
        writeChunk(out, "IHDR", ihdr(width, height, 8, COLOR_TYPE_INDEXED));
        writeChunk(out, "PLTE", paletteEntries(palette));
        if (palette.hasAlpha()) {
            writeChunk(out, "tRNS", paletteAlpha(palette));
        }
        writeIdat(out, idat);
        writeChunk(out, "IEND", null);
        return out.toByteArray();
    }

    /**
     * 提取调色板 RGB 条目
     *
     * @param palette 调色板
     * @return 每 3 字节一个 RGB 的条目数组
     */
    private static byte[] paletteEntries(IndexColorModel palette) {
        int count = palette.getMapSize();
        byte[] entries = new byte[count * 3];
        byte[] reds = new byte[count];
        byte[] greens = new byte[count];
        byte[] blues = new byte[count];
        palette.getReds(reds);
        palette.getGreens(greens);
        palette.getBlues(blues);
        for (int i = 0; i < count; i++) {
            entries[i * 3] = reds[i];
            entries[i * 3 + 1] = greens[i];
            entries[i * 3 + 2] = blues[i];
        }
        return entries;
    }

    /**
     * 提取调色板 Alpha 条目
     *
     * @param palette 调色板
     * @return 每色一个 Alpha 值的数组
     */
    private static byte[] paletteAlpha(IndexColorModel palette) {
        int count = palette.getMapSize();
        byte[] alphas = new byte[count];
        palette.getAlphas(alphas);
        return alphas;
    }

    /**
     * 以真彩色（必要时无损降级为灰度）写出 PNG。
     *
     * <p>当图像不含任何彩色信息（R==G==B）且不含 Alpha 时，自动降级为色彩类型 0；
     * 含 Alpha 时降级为色彩类型 4。这两种降级都是<b>逐像素完全无损</b>的。
     *
     * @param image    源图像
     * @param engine   deflate 引擎
     * @param profile  deflate 压缩档位
     * @param strategy 过滤器选择策略
     * @return 完整的 PNG 文件字节
     * @throws IOException 当写出失败时
     * @throws Exception  当 deflate 引擎执行失败时
     */
    public static byte[] writeTrueColor(BufferedImage image, DeflateEngine engine,
                                        DeflateEngine.DeflateProfile profile,
                                        PngFilterOptimizer.Strategy strategy) throws IOException, Exception {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);

        boolean hasAlpha = false;
        boolean colored = false;
        for (int pixel : argb) {
            if ((pixel >>> 24) != 0xFF) {
                hasAlpha = true;
            }
            int r = (pixel >>> 16) & 0xFF;
            int g = (pixel >>> 8) & 0xFF;
            int b = pixel & 0xFF;
            if (r != g || g != b) {
                colored = true;
            }
            if (hasAlpha && colored) {
                break;
            }
        }

        int colorType = colored ? (hasAlpha ? COLOR_TYPE_RGBA : COLOR_TYPE_RGB)
                : (hasAlpha ? COLOR_TYPE_GRAY_ALPHA : COLOR_TYPE_GRAY);
        int channels = switch (colorType) {
            case COLOR_TYPE_GRAY -> 1;
            case COLOR_TYPE_GRAY_ALPHA -> 2;
            case COLOR_TYPE_RGB -> 3;
            default -> 4;
        };

        byte[] scanlines = new byte[height * (1 + width * channels)];
        for (int row = 0; row < height; row++) {
            int target = row * (1 + width * channels) + 1;
            for (int column = 0; column < width; column++) {
                int pixel = argb[row * width + column];
                int r = (pixel >>> 16) & 0xFF;
                int g = (pixel >>> 8) & 0xFF;
                int b = pixel & 0xFF;
                int a = (pixel >>> 24) & 0xFF;
                switch (colorType) {
                    case COLOR_TYPE_GRAY -> scanlines[target + column] = (byte) r;
                    case COLOR_TYPE_GRAY_ALPHA -> {
                        scanlines[target + column * 2] = (byte) r;
                        scanlines[target + column * 2 + 1] = (byte) a;
                    }
                    case COLOR_TYPE_RGB -> {
                        scanlines[target + column * 3] = (byte) r;
                        scanlines[target + column * 3 + 1] = (byte) g;
                        scanlines[target + column * 3 + 2] = (byte) b;
                    }
                    default -> {
                        scanlines[target + column * 4] = (byte) r;
                        scanlines[target + column * 4 + 1] = (byte) g;
                        scanlines[target + column * 4 + 2] = (byte) b;
                        scanlines[target + column * 4 + 3] = (byte) a;
                    }
                }
            }
        }

        byte[] filtered = PngFilterOptimizer.filter(scanlines, width * channels, height, channels, strategy, 4096);
        byte[] idat = engine.deflate(filtered, profile);

        ByteArrayOutputStream out = new ByteArrayOutputStream(idat.length + 1024);
        out.write(SIGNATURE);
        writeChunk(out, "IHDR", ihdr(width, height, 8, colorType));
        if (colorType == COLOR_TYPE_GRAY) {
            // 色彩类型 0 的 Alpha 由 tRNS 块里的「灰色样本值」表达；
            // 色彩类型 4/6 的 Alpha 已经逐像素写在扫描线里，不再需要 tRNS。
            int transparentGray = -1;
            for (int pixel : argb) {
                int a = (pixel >>> 24) & 0xFF;
                if (a != 0xFF) {
                    transparentGray = a == 0 ? ((pixel >>> 16) & 0xFF) : -1;
                    break;
                }
            }
            if (transparentGray >= 0) {
                writeChunk(out, "tRNS", new byte[]{(byte) transparentGray});
            }
        }
        writeIdat(out, idat);
        writeChunk(out, "IEND", null);
        return out.toByteArray();
    }

    /**
     * 构造 IHDR 块内容
     *
     * @param width     宽度
     * @param height    高度
     * @param bitDepth  位深
     * @param colorType 色彩类型
     * @return 13 字节的 IHDR 数据
     */
    private static byte[] ihdr(int width, int height, int bitDepth, int colorType) {
        byte[] data = new byte[13];
        writeInt(data, 0, width);
        writeInt(data, 4, height);
        data[8] = (byte) bitDepth;
        data[9] = (byte) colorType;
        data[10] = 0;
        data[11] = 0;
        data[12] = 0;
        return data;
    }

    /**
     * 写出 IDAT 数据流，按上限切块
     *
     * @param out  输出流
     * @param idat deflate 后的完整数据流
     * @throws IOException 当写出失败时
     */
    private static void writeIdat(ByteArrayOutputStream out, byte[] idat) throws IOException {
        int offset = 0;
        while (offset < idat.length) {
            int length = Math.min(IDAT_CHUNK_SIZE, idat.length - offset);
            writeChunk(out, "IDAT", java.util.Arrays.copyOfRange(idat, offset, offset + length));
            offset += length;
        }
    }

    /**
     * 写出一个 PNG chunk（长度 + 类型 + 数据 + CRC）
     *
     * @param out   输出流
     * @param type  块类型，4 个 ASCII 字符
     * @param data  块数据，{@code null} 表示空块
     * @throws IOException 当写出失败时
     */
    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] payload = data == null ? new byte[0] : data;
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);

        byte[] length = new byte[4];
        writeInt(length, 0, payload.length);
        out.write(length);
        out.write(typeBytes);
        out.write(payload);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(payload);
        byte[] checksum = new byte[4];
        writeInt(checksum, 0, (int) crc.getValue());
        out.write(checksum);
    }

    /**
     * 写入 4 字节大端整数
     *
     * @param target 目标数组
     * @param offset 起始偏移
     * @param value  值
     */
    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}