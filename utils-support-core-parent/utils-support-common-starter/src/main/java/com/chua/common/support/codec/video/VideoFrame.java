package com.chua.common.support.codec.video;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * 视频原始帧 —— 携带像素平面缓冲的不可变载体。
 *
 * <p>平面缓冲区既可以是直接内存（{@code ByteBuffer.allocateDirect}，供原生编码器零拷贝使用），
 * 也可以是堆内存。每个平面的有效数据从 {@link ByteBuffer#position()} 开始读取
 * {@code remaining()} 字节，行跨距由 {@link #lineSize(int)} 描述。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class VideoFrame {

    /**
     * 帧宽，单位像素。
     */
    private final int width;

    /**
     * 帧高，单位像素。
     */
    private final int height;

    /**
     * 像素格式。
     */
    private final PixelFormat pixelFormat;

    /**
     * 各平面数据缓冲。
     */
    private final ByteBuffer[] planes;

    /**
     * 各平面行跨距，单位字节。
     */
    private final int[] lineSizes;

    /**
     * 展示时间戳，单位微秒。
     */
    private final long pts;

    /**
     * 构造视频帧。
     *
     * @param width       帧宽
     * @param height      帧高
     * @param pixelFormat 像素格式
     * @param planes      各平面缓冲
     * @param lineSizes   各平面行跨距
     * @param pts         展示时间戳（微秒）
     */
    private VideoFrame(int width, int height, PixelFormat pixelFormat,
                       ByteBuffer[] planes, int[] lineSizes, long pts) {
        this.width = width;
        this.height = height;
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.planes = planes.clone();
        this.lineSizes = lineSizes.clone();
        this.pts = pts;
        if (planes.length != pixelFormat.planes() || lineSizes.length != pixelFormat.planes()) {
            throw new IllegalArgumentException("plane count mismatch, expected "
                    + pixelFormat.planes() + " but got " + planes.length);
        }
    }

    /**
     * 组装任意像素格式的帧。
     *
     * @param pixelFormat 像素格式
     * @param width       帧宽
     * @param height      帧高
     * @param lineSizes   各平面行跨距
     * @param planes      各平面缓冲，顺序与 {@code pixelFormat} 的平面定义一致
     * @param pts         展示时间戳（微秒）
     * @return 视频帧
     */
    public static VideoFrame of(PixelFormat pixelFormat, int width, int height,
                               int[] lineSizes, ByteBuffer[] planes, long pts) {
        return new VideoFrame(width, height, pixelFormat, planes, lineSizes, pts);
    }

    /**
     * 组装 YUV420P 帧。
     *
     * @param y       Y 平面
     * @param u       U 平面
     * @param v       V 平面
     * @param lineY   Y 行跨距
     * @param lineU   U 行跨距
     * @param lineV   V 行跨距
     * @param width   帧宽
     * @param height  帧高
     * @param pts     展示时间戳（微秒）
     * @return 视频帧
     */
    public static VideoFrame yuv420p(ByteBuffer y, ByteBuffer u, ByteBuffer v,
                                     int lineY, int lineU, int lineV,
                                     int width, int height, long pts) {
        return new VideoFrame(width, height, PixelFormat.YUV420P,
                new ByteBuffer[]{y, u, v}, new int[]{lineY, lineU, lineV}, pts);
    }

    /**
     * 组装 NV12 帧。
     *
     * @param y       Y 平面
     * @param uv      UV 交织平面
     * @param lineY   Y 行跨距
     * @param lineUv  UV 行跨距
     * @param width   帧宽
     * @param height  帧高
     * @param pts     展示时间戳（微秒）
     * @return 视频帧
     */
    public static VideoFrame nv12(ByteBuffer y, ByteBuffer uv, int lineY, int lineUv,
                                  int width, int height, long pts) {
        return new VideoFrame(width, height, PixelFormat.NV12,
                new ByteBuffer[]{y, uv}, new int[]{lineY, lineUv}, pts);
    }

    /**
     * 获取帧宽。
     *
     * @return 帧宽，单位像素
     */
    public int width() {
        return width;
    }

    /**
     * 获取帧高。
     *
     * @return 帧高，单位像素
     */
    public int height() {
        return height;
    }

    /**
     * 获取像素格式。
     *
     * @return 像素格式
     */
    public PixelFormat pixelFormat() {
        return pixelFormat;
    }

    /**
     * 获取展示时间戳。
     *
     * @return 时间戳，单位微秒
     */
    public long pts() {
        return pts;
    }

    /**
     * 获取平面数量。
     *
     * @return 平面数量
     */
    public int planeCount() {
        return planes.length;
    }

    /**
     * 获取指定平面缓冲。
     *
     * @param index 平面下标
     * @return 平面缓冲
     */
    public ByteBuffer plane(int index) {
        return planes[index];
    }

    /**
     * 获取指定平面行跨距。
     *
     * @param index 平面下标
     * @return 行跨距，单位字节
     */
    public int lineSize(int index) {
        return lineSizes[index];
    }

    /**
     * 估算该帧占用的字节数（按行跨距与高度累加，色度平面高度减半）。
     *
     * @return 字节数
     */
    public long byteSize() {
        long total = 0;
        for (int i = 0; i < lineSizes.length; i++) {
            int vertical = pixelFormat == PixelFormat.YUV420P && i > 0
                    ? (height + 1) / 2 : height;
            total += (long) lineSizes[i] * vertical;
        }
        return total;
    }

    @Override
    public String toString() {
        return "VideoFrame{" + pixelFormat + " " + width + "x" + height
                + ", lineSizes=" + Arrays.toString(lineSizes) + ", pts=" + pts + "}";
    }
}
