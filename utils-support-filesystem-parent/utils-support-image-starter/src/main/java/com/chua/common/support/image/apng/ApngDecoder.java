package com.chua.common.support.image.apng;

import com.chua.common.support.image.png.PNG;

import javax.annotation.Nonnull;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
* APNG（Animated PNG）解析器：读取 actl/fcTL/fdat/IDAT 块并还原动画帧。
*
* <p>对齐 {@code GifDecoder} 的调用风格：
* <pre>{@code
* ApngDecoder decoder = new ApngDecoder();
* decoder.read(inputStream);
* for (int i = 0; i < decoder.getFrameCount(); i++) {
*     BufferedImage frame = decoder.getFrame(i);
*     int delayMs = decoder.getDelay(i);
* }
* }</pre>.getFrame(i);
*     int delayMs = decoder.getDelay(i);
* }
* }</pre>
*
* <p>支持标准 APNG 特性：帧偏移（fcTL x/y）、处置操作（dispose_op）、
* 混合操作（blend_op，源/OVER）、循环次数（actl num_plays）。
* 帧数据统一解码为 RGBA 颜色类型。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class ApngDecoder {

    /** PNG 文件签名 */
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    /** 块类型字节（大端 int） */
    private static final int CHUNK_IHDR = 0x49484452; // IHDR
    private static final int CHUNK_acTL = 0x6163544C; // actl
    private static final int CHUNK_fcTL = 0x6663544C; // fcTL
    private static final int CHUNK_IDAT = 0x49444154; // IDAT
    private static final int CHUNK_fdAT = 0x66644154; // fdat
    private static final int CHUNK_IEND = 0x49454E44; // IEND

    /** 解码后的帧列表 */
    private final List<BufferedImage> frames = new ArrayList<>();

    /** 每帧延迟（毫秒） */
    private final List<Integer> delays = new ArrayList<>();

    /** 循环次数（actl num_plays），0 表示无限循环 */
    private int loopCount;

    /** 画布宽度 */
    private int width;

    /** 画布高度 */
    private int height;

    /** 是否为 APNG（含 actl 块） */
    private boolean animated;

    /** 是否已解析 */
    private boolean read;

    /**
    * 从输入流解析 APNG。
    *
    * @param is 输入流，不能为空
    * @throws IOException 解析失败时抛出
    */
    public void read(@Nonnull InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(is);

        // 校验 PNG 签名
        byte[] sig = new byte[8];
        in.readFully(sig);
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (sig[i] != PNG_SIGNATURE[i]) {
                throw new IOException("不是有效的 PNG 文件（签名不匹配）");
            }
        }

        List<FrameData> frameDataList = new ArrayList<>();
        FrameData current = null;
        ByteArrayOutputStream idatBuffer = new ByteArrayOutputStream();

        // 逐块解析
        while (true) {
            int length = in.readInt();
            int type = in.readInt();
            byte[] data = new byte[length];
            in.readFully(data);
            in.readInt(); // CRC（本解析器不校验 CRC）

            switch (type) {
                case CHUNK_IHDR:
                    width = readIntBE(data, 0);
                    height = readIntBE(data, 4);
                    break;

                case CHUNK_acTL:
                    animated = true;
                    loopCount = readIntBE(data, 4); // num_plays
                    break;

                case CHUNK_fcTL:
                    // 归档上一帧数据（避免重复添加），并开始新帧
                    if (current != null && !current.added) {
                        frameDataList.add(current);
                    }
                    current = new FrameData();
                    current.control = parseFrameControl(data);
                    break;

                case CHUNK_IDAT:
                    if (current == null) {
 // 无 fcTL 的 IDAT：静态 PNG 或首帧数据
                        current = new FrameData();
                        current.control = null; // 首帧使用全画布
                        current.data = new ByteArrayOutputStream();
                        current.data.write(data);
                        frameDataList.add(current);
                        current.added = true;
                    } else {
                        current.data.write(data);
                    }
                    break;

                case CHUNK_fdAT:
                    if (current == null) {
                        throw new IOException("fdAT 块出现在帧数据之前");
                    }
 // fdat: 4 字节 sequence + 压缩数据
                    current.data.write(data, 4, data.length - 4);
                    break;

                case CHUNK_IEND:
                    if (current != null && !current.added) {
                        frameDataList.add(current);
                    }
                    decodeFrames(frameDataList);
                    read = true;
                    return;

                default:
 // 其他块（PLTE/文本 等）忽略
                    break;
            }
        }
    }

    // ==================== 帧合成 ====================

    /**
    * 解码所有帧：zlib 解压 → 逐行 unfilter → 按 fcTL 合成到画布。
    * @param frameDataList 帧数据列表
    */
    private void decodeFrames(List<FrameData> frameDataList) throws IOException {
        if (frameDataList.isEmpty()) {
            return;
        }
        int canvasW = width;
        int canvasH = height;

        // 当前画布
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        clearCanvas(canvas);

        for (FrameData fd : frameDataList) {
            if (fd.control == null) {
 // 首帧（无 fcTL）：整幅画布
                BufferedImage frameImage = decodeFrameData(fd.data.toByteArray(), canvasW, canvasH);
                frames.add(frameImage);
                delays.add(0);
                canvas = copyImage(frameImage);
                continue;
            }

            FrameControl ctrl = fd.control;
            BufferedImage raw = decodeFrameData(fd.data.toByteArray(), ctrl.width, ctrl.height);

            // 处置上一帧
            BufferedImage previous = copyImage(canvas);
            if (ctrl.disposeOp == PNG.APNG_DISPOSE_OP_BACKGROUND) {
                clearRegion(canvas, ctrl.xOffset, ctrl.yOffset, ctrl.width, ctrl.height);
            } else if (ctrl.disposeOp == PNG.APNG_DISPOSE_OP_PREVIOUS) {
                canvas = newCanvas(canvasW, canvasH);
            }

            // 混合当前帧
            compositeFrame(canvas, raw, ctrl.xOffset, ctrl.yOffset, ctrl.blendOp);

            frames.add(copyImage(canvas));
            delays.add(ctrl.delayMillis());
        }
    }

    /**
    * 解码单帧数据：zlib 解压后按 PNG 扫描线格式还原像素。
    *
    * @param rawData 压缩的帧数据（IDAT/fdat 内容）
    * @param w       帧宽
    * @param h       帧高
    * @return RGBA 图像
    */
    private BufferedImage decodeFrameData(byte[] rawData, int w, int h) throws IOException {
        byte[] unfiltered = inflate(rawData);
        int bpp = 4; // RGBA
        int stride = w * bpp;

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();

        byte[] prevRow = new byte[stride];
        byte[] currRow = new byte[stride];
        int offset = 0;
        for (int y = 0; y < h; y++) {
            int filterType = unfiltered[offset++] & 0xFF;
            System.arraycopy(unfiltered, offset, currRow, 0, stride);
            offset += stride;
            unFilterRow(filterType, currRow, prevRow, stride, bpp);
            for (int x = 0; x < w; x++) {
                int idx = x * 4;
                int a = currRow[idx + 3] & 0xFF;
                int r = currRow[idx] & 0xFF;
                int g = currRow[idx + 1] & 0xFF;
                int b = currRow[idx + 2] & 0xFF;
                pixels[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            byte[] tmp = prevRow;
            prevRow = currRow;
            currRow = tmp;
        }
        return image;
    }

    /**
    * zlib 解压。
    * @param data 数据
    * @return inflate的结果
    */
    private static byte[] inflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2);
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                    throw new IOException("zlib 解压数据损坏");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("zlib 解压失败: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

/**
    * PNG 行过滤器逆变换（unfilter）。
    * @param filterType 过滤器类型
    * @param curr curr
    * @param prev prev
    * @param stride stride
    * @param bpp bpp
    */
    private static void unFilterRow(int filterType, byte[] curr, byte[] prev, int stride, int bpp) {
        switch (filterType) {
            case PNG.PNG_FILTER_NONE:
                break;
            case PNG.PNG_FILTER_SUB:
                for (int i = bpp; i < stride; i++) {
                    curr[i] = (byte) ((curr[i] & 0xFF) + (curr[i - bpp] & 0xFF));
                }
                break;
            case PNG.PNG_FILTER_UP:
                for (int i = 0; i < stride; i++) {
                    curr[i] = (byte) ((curr[i] & 0xFF) + (prev[i] & 0xFF));
                }
                break;
            case PNG.PNG_FILTER_AVERAGE:
                for (int i = 0; i < stride; i++) {
                    int left = i >= bpp ? (curr[i - bpp] & 0xFF) : 0;
                    int up = prev[i] & 0xFF;
                    curr[i] = (byte) ((curr[i] & 0xFF) + ((left + up) >>> 1));
                }
                break;
            case PNG.PNG_FILTER_PAETH:
                for (int i = 0; i < stride; i++) {
                    int a = i >= bpp ? (curr[i - bpp] & 0xFF) : 0;
                    int b = prev[i] & 0xFF;
                    int c = i >= bpp ? (prev[i - bpp] & 0xFF) : 0;
                    curr[i] = (byte) ((curr[i] & 0xFF) + paethPredictor(a, b, c));
                }
                break;
            default:
                break;
        }
    }

    private static int paethPredictor(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        if (pb <= pc) {
            return b;
        }
        return c;
    }

    // ==================== 画布操作 ====================

    /**
    * 新Canvas。
    * @param w w
    * @param h h
    * @return 新Canvas的结果
    */
    private static BufferedImage newCanvas(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    /**
    * clearCanvas。
    * @param image 镜像
    */
    private static void clearCanvas(BufferedImage image) {
        int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
        java.util.Arrays.fill(pixels, 0);
    }

    /**
    * clearregion。
    * @param image 镜像
    * @param x x
    * @param y y
    * @param w w
    * @param h h
    */
    private static void clearRegion(BufferedImage image, int x, int y, int w, int h) {
        for (int yy = y; yy < y + h && yy < image.getHeight(); yy++) {
            for (int xx = x; xx < x + w && xx < image.getWidth(); xx++) {
                image.setRGB(xx, yy, 0);
            }
        }
    }

    /**
    * 副本镜像。
    * @param src src
    * @return 副本镜像的结果
    */
    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((java.awt.image.DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        System.arraycopy(((java.awt.image.DataBufferInt) src.getRaster().getDataBuffer()).getData(),
                0, pixels, 0, pixels.length);
        return dst;
    }

    /**
    * 将一帧绘制到画布指定偏移处。
    *
    * @param canvas 画布
    * @param frame  帧图像
    * @param x      水平偏移
    * @param y      垂直偏移
    * @param blend  混合方式（源=覆盖，OVER=Alpha 混合）
    * @param dst dst
    * @param src src
    * @param srcAlpha srcalpha
    * @return blendOver的结果
    */
    private static void compositeFrame(BufferedImage canvas, BufferedImage frame,
                                       int x, int y, int blendOp) {
        int w = Math.min(frame.getWidth(), canvas.getWidth() - x);
        int h = Math.min(frame.getHeight(), canvas.getHeight() - y);
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int argb = frame.getRGB(xx, yy);
                int dstX = x + xx;
                int dstY = y + yy;
                if (blendOp == PNG.APNG_BLEND_OP_OVER) {
                    int a = (argb >>> 24) & 0xFF;
                    if (a == 255) {
                        canvas.setRGB(dstX, dstY, argb);
                    } else if (a > 0) {
                        int dst = canvas.getRGB(dstX, dstY);
                        canvas.setRGB(dstX, dstY, blendOver(dst, argb, a));
                    }
                } else {
                    canvas.setRGB(dstX, dstY, argb);
                }
            }
        }
    }

    private static int blendOver(int dst, int src, int srcAlpha) {
        int dr = (dst >>> 16) & 0xFF, dg = (dst >>> 8) & 0xFF, db = dst & 0xFF;
        int sr = (src >>> 16) & 0xFF, sg = (src >>> 8) & 0xFF, sb = src & 0xFF;
        int inv = 255 - srcAlpha;
        int r = (sr * srcAlpha + dr * inv + 127) / 255;
        int g = (sg * srcAlpha + dg * inv + 127) / 255;
        int b = (sb * srcAlpha + db * inv + 127) / 255;
        int a = srcAlpha + ((dst >>> 24) & 0xFF) * inv / 255;
        return (Math.min(a, 255) << 24) | (r << 16) | (g << 8) | b;
    }

    // ==================== 数据类 ====================

    /**
    * fcTL 帧控制块。
    */
    private static final class FrameControl {
        int width;
        int height;
        int xOffset;
        int yOffset;
        int delayNum;
        int delayDen;
        int disposeOp;
        int blendOp;

        int delayMillis() {
            if (delayNum == 0) {
                return 0;
            }
            int den = delayDen == 0 ? 100 : delayDen;
            return (int) Math.round(delayNum * 1000.0 / den);
        }
    }

    /**
    * 单帧数据：帧控制 + 压缩数据。
    */
    private static final class FrameData {
        FrameControl control;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        /**
        * 是否已加入帧列表（防止重复添加）
        *
        * @param data 数据
        * @return 解析帧control的结果
        */
        boolean added;
    }

    private static FrameControl parseFrameControl(byte[] data) {
        FrameControl ctrl = new FrameControl();
        if (data.length >= 30) {
            // 标准 30 字节：seq(4) + width/height/x/y(16) + delay_num/den(8) + dispose/blend(2)
            ctrl.width = readIntBE(data, 4);
            ctrl.height = readIntBE(data, 8);
            ctrl.xOffset = readIntBE(data, 12);
            ctrl.yOffset = readIntBE(data, 16);
            ctrl.delayNum = readIntBE(data, 20);
            ctrl.delayDen = readIntBE(data, 24);
            ctrl.disposeOp = data[28] & 0xFF;
            ctrl.blendOp = data[29] & 0xFF;
        } else if (data.length >= 26) {
            // PIL 变体 26 字节：seq(4) + width/height/x/y(16) + delay_num/den(各 2 字节) + dispose/blend(2)
            ctrl.width = readIntBE(data, 4);
            ctrl.height = readIntBE(data, 8);
            ctrl.xOffset = readIntBE(data, 12);
            ctrl.yOffset = readIntBE(data, 16);
            ctrl.delayNum = ((data[20] & 0xFF) << 8) | (data[21] & 0xFF);
            ctrl.delayDen = ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
            ctrl.disposeOp = data[24] & 0xFF;
            ctrl.blendOp = data[25] & 0xFF;
        }
        return ctrl;
    }

    /**
    * 读取intbe。
    * @param data 数据
    * @param offset 偏移量
    * @return 读取intbe的结果
    */
    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    // ==================== 访问器 ====================

    /**
    * 获取帧数量。
    *
    * @return 帧数量，未解析时返回 0
    */
    public int getFrameCount() {
        return frames.size();
    }

    /**
    * 获取指定帧。
    *
    * @param index 帧索引
    * @return 帧图像
    */
    @Nonnull
    public BufferedImage getFrame(int index) {
        return frames.get(index);
    }

    /**
    * 获取指定帧的延迟（毫秒）。
    *
    * @param index 帧索引
    * @return 延迟毫秒数
    */
    public int getDelay(int index) {
        return delays.get(index);
    }

    /**
    * 获取循环次数，0 表示无限循环。
    *
    * @return 循环次数
    */
    public int getLoopCount() {
        return loopCount;
    }

    /**
    * 获取画布宽度。
    *
    * @return 宽度
    */
    public int getWidth() {
        return width;
    }

    /**
    * 获取画布高度。
    *
    * @return 高度
    */
    public int getHeight() {
        return height;
    }

    /**
    * 判断是否为动画（含 actl 块）。
    *
    * @return true 表示 APNG
    */
    public boolean isAnimated() {
        return animated;
    }

    /**
    * 是否已成功解析。
    *
    * @return true 表示已解析
    */
    public boolean isRead() {
        return read;
    }
}
