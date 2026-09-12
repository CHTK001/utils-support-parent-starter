package com.chua.common.support.image.apng;

import com.chua.common.support.image.png.PNG;

import javax.annotation.Nonnull;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
* APNG（Animated PNG）编码器：将多帧图像合成为 APNG 文件。
*
* <p>对齐 {@code GifEncoder} 的调用风格：
* <pre>{@code
* ApngEncoder encoder = new ApngEncoder(outputStream);
* encoder.setLoopCount(0);                    // 无限循环
* encoder.addFrame(frame1, 100);              // 每帧 100ms
* encoder.addFrame(frame2, 100);
* encoder.finish();
* }</pre>er.addFrame(frame2, 100);
* encoder.finish();
* }</pre>
*
* <p>输出格式：8-bit RGBA（颜色类型 6），首帧数据写入 IDAT，后续帧写入 fdAT，
* 帧控制信息写入 函数计算tl（整帧绘制：x/y=0，dispose=无，blend=源）。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class ApngEncoder {

    /** PNG 文件签名 */
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    /** 帧列表 */
    private final List<BufferedImage> frames = new ArrayList<>();

    /** 每帧延迟（毫秒） */
    private final List<Integer> delays = new ArrayList<>();

    /** 循环次数，0 表示无限循环 */
    private int loopCount;

    /** 输出流 */
    private DataOutputStream out;

    /**
    * 创建编码器。
     */
    public ApngEncoder() {
    }

    /**
    * 创建编码器并绑定输出流。
    *
    * @param output 输出流
    * @throws IOException IO 异常
     */
    public ApngEncoder(@Nonnull OutputStream output) throws IOException {
        this.out = new DataOutputStream(output);
    }

    /**
    * 绑定输出流（未使用构造器绑定时调用）。
    *
    * @param output 输出流
    * @return this
    * @throws IOException IO 异常
     */
    @Nonnull
    public ApngEncoder start(@Nonnull OutputStream output) throws IOException {
        this.out = new DataOutputStream(output);
        return this;
    }

    /**
    * 添加一帧。
    *
    * @param frame       帧图像（RGBA）
    * @param delayMillis 帧延迟（毫秒），负值按 0 处理
    * @return this
     */
    @Nonnull
    public ApngEncoder addFrame(@Nonnull BufferedImage frame, int delayMillis) {
        frames.add(frame);
        delays.add(Math.max(0, delayMillis));
        return this;
    }

    /**
    * 设置循环次数。
    *
    * @param loopCount 循环次数，0 表示无限循环
    * @return this
     */
    @Nonnull
    public ApngEncoder setLoopCount(int loopCount) {
        this.loopCount = Math.max(0, loopCount);
        return this;
    }

    /**
    * 完成编码：写出全部 APNG 块并刷新输出流。
    *
    * @throws IOException IO 异常
     */
    public void finish() throws IOException {
        if (out == null) {
            throw new IOException("未绑定输出流，请使用构造器或 start(OutputStream)");
        }
        if (frames.isEmpty()) {
            throw new IOException("没有帧数据，请先调用 addFrame");
        }

        BufferedImage first = frames.get(0);
        int width = first.getWidth();
        int height = first.getHeight();

        out.write(PNG_SIGNATURE);
        writeIHDR(width, height);

 // actl：总帧数 + 播放次数
        byte[] acTL = new byte[8];
        putIntBE(acTL, 0, frames.size());
        putIntBE(acTL, 4, loopCount);
        writeChunk("acTL", acTL);

        int sequence = 0;
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage frame = frames.get(i);
            int delayMs = delays.get(i);

            // fcTL：帧控制（30 字节 = sequence(4) + 控制数据(26)）
            writeChunk("fcTL", buildFcTL(sequence++, frame.getWidth(), frame.getHeight(), delayMs));

 // 帧数据：首帧 IDAT（不占 sequence），后续帧 fdat（带 sequence）
            byte[] compressed = compressFrame(frame, width, height);
            if (i == 0) {
                writeChunk("IDAT", compressed);
            } else {
                byte[] fdAT = new byte[4 + compressed.length];
                putIntBE(fdAT, 0, sequence++);
                System.arraycopy(compressed, 0, fdAT, 4, compressed.length);
                writeChunk("fdAT", fdAT);
            }
        }

        writeChunk("IEND", new byte[0]);
        out.flush();
    }

    // ==================== 块写入 ====================

    /**
    * 写入 IHDR 块：8-钻头 RGBA（颜色类型 6），无隔行。
    * @param width width
    * @param height height
     */
    private void writeIHDR(int width, int height) throws IOException {
        byte[] ihdr = new byte[13];
        putIntBE(ihdr, 0, width);
        putIntBE(ihdr, 4, height);
        ihdr[8] = 8; // 钻头 深度
        ihdr[9] = PNG.PNG_COLOR_RGB_ALPHA; // color 类型 6
        ihdr[10] = 0;                 // compression
        ihdr[11] = 0; // 过滤器
        ihdr[12] = 0;                 // interlace
        writeChunk("IHDR", ihdr);
    }

    /**
    * 构建 函数计算tl 块数据（30 字节）：sequence(4) + width/height/x/y/延迟(20) + dispose/blend(2)。
    * 整帧绘制（x/y=0），dispose=无，blend=源。
    * @param sequence sequence
    * @param width width
    * @param height height
    * @param delayMillis 延迟millis
    * @return 构建函数计算tl的结果
     */
    private static byte[] buildFcTL(int sequence, int width, int height, int delayMillis) {
        byte[] fcTL = new byte[30];
        putIntBE(fcTL, 0, sequence);
        putIntBE(fcTL, 4, width);
        putIntBE(fcTL, 8, height);
        putIntBE(fcTL, 12, 0); // x_偏移量
        putIntBE(fcTL, 16, 0); // y_偏移量
        // delay：delay_num = 毫秒，delay_den = 1000 → 精确毫秒
        putIntBE(fcTL, 20, delayMillis);
        putIntBE(fcTL, 24, 1000);
        fcTL[28] = PNG.APNG_DISPOSE_OP_NONE;
        fcTL[29] = PNG.APNG_BLEND_OP_SOURCE;
        return fcTL;
    }

    /**
    * 写入一个 PNG 块：长度 + 类型 + 数据 + CRC。
    * @param type 类型
    * @param data 数据
     */
    private void writeChunk(String type, byte[] data) throws IOException {
        out.writeInt(data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(typeBytes);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }

    /**
    * 压缩一帧为 PNG 扫描线数据：每行 过滤器=0 + RGBA 像素，zlib 压缩。
    * @param data 数据
     /**
      * compress帧。
      * @param frame 帧
      * @param canvasW Canvasw
      * @param canvasH Canvash
      * @return compress帧的结果
      */
     * @param offset 偏移量
     * @param value 值
      * @param data 数据
     /**
     * compress帧。
     * @param frame 帧
     * @param canvasW Canvasw
     * @param canvasH Canvash
     * @return compress帧的结果
      */
     */
    private static byte[] compressFrame(BufferedImage frame, int canvasW, int canvasH) throws IOException {
        int w = frame.getWidth();
        int h = frame.getHeight();
        int stride = w * 4;

        ByteArrayOutputStream raw = new ByteArrayOutputStream((stride + 1) * h);
        int[] pixels;
        if (frame.getType() == BufferedImage.TYPE_INT_ARGB) {
            pixels = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        } else {
            pixels = frame.getRGB(0, 0, w, h, null, 0, w);
        }

        for (int y = 0; y < h; y++) {
            raw.write(0); // 过滤器: 无
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int argb = pixels[base + x];
                raw.write((argb >> 16) & 0xFF); // R
                raw.write((argb >> 8) & 0xFF);  // G
                raw.write(argb & 0xFF);         // B
                raw.write((argb >>> 24) & 0xFF);// A
            }
        }

        byte[] rawData = raw.toByteArray();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(rawData);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(rawData.length);
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                compressed.write(buf, 0, n);
            }
            return compressed.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void putIntBE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}
