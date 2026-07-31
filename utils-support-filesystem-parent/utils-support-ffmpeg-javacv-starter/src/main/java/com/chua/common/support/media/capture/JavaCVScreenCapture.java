package com.chua.common.support.media.capture;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.media.codec.ScreenCature;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * 基于 JavaCV(FFmpeg) 的屏幕采集器，根据平台自动选择采集器。
 *
 * <p>返回 BufferedImage 以保证广泛兼容性。平台自动检测：</p>
 * <ul>
 * <li>Windows → gdigrab</li>
 * <li>Linux → x11grab</li>
 * <li>macOS → avfoundation</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("javacv")
public class JavaCVScreenCapture implements ScreenCature {

    /**
     * FFmpeg 帧采集器
     */
    private FFmpegFrameGrabber grabber;

    /**
     * 采集宽度
     */
    private int width;

    /**
     * 采集高度
     */
    private int height;

    /**
     * 采集帧率
     */
    private int fps;

    /**
     * 采集器是否已初始化
     */
    private volatile boolean initialized;

    /**
     * Windows 屏幕采集源标识
     */
    private static final String SOURCE_WINDOWS = "desktop";

    /**
     * Windows 采集格式
     */
    private static final String FORMAT_WINDOWS = "gdigrab";

    /**
     * Linux 屏幕采集源标识
     */
    private static final String SOURCE_LINUX = ":0.0";

    /**
     * Linux 采集格式
     */
    private static final String FORMAT_LINUX = "x11grab";

    /**
     * macOS 屏幕采集源标识
     */
    private static final String SOURCE_MACOS = "1";

    /**
     * macOS 采集格式
     */
    private static final String FORMAT_MACOS = "avfoundation";

    /**
     * 探测大小（42M）
     */
    private static final String PROBESIZE = "42M";

    /**
     * 分析时长（0 表示最小分析）
     */
    private static final String ANALYZE_DURATION = "0";

    @Override
    public boolean init(int width, int height, int fps) {
        close();
        try {
            String source;
            String format;
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                source = SOURCE_WINDOWS;
                format = FORMAT_WINDOWS;
            } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                source = SOURCE_LINUX;
                format = FORMAT_LINUX;
            } else if (os.contains("mac")) {
                source = SOURCE_MACOS;
                format = FORMAT_MACOS;
            } else {
                log.error("[JavaCVScreenCapture] 不支持的操作系统: {}", os);
                return false;
            }

            grabber = new FFmpegFrameGrabber(source);
            grabber.setFormat(format);
            grabber.setImageWidth(width);
            grabber.setImageHeight(height);
            grabber.setFrameRate(Math.min(60, Math.max(1, fps)));
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
            grabber.setOption("probesize", PROBESIZE);
            grabber.setOption("analyzeduration", ANALYZE_DURATION);
            grabber.start();

            this.width = grabber.getImageWidth();
            this.height = grabber.getImageHeight();
            this.fps = fps;
            this.initialized = true;

            log.info("[JavaCVScreenCapture] 已启动: format={} source={} {}x{} {}fps",
                    format, source, this.width, this.height, fps);
            return true;
        } catch (Exception e) {
            log.error("[JavaCVScreenCapture] 初始化失败: {}", e.getMessage(), e);
            close();
            return false;
        }
    }

    @Override
    public java.awt.image.BufferedImage grabFrame() {
        if (!initialized || grabber == null) {
            return null;
        }
        try {
            Frame frame = grabber.grabImage();
            if (frame == null || frame.image == null) {
                return null;
            }
            return frameToBufferedImage(frame);
        } catch (Exception e) {
            log.warn("[JavaCVScreenCapture] 采集失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 FFmpeg 帧转换为 BufferedImage。
     *
     * @param frame FFmpeg 帧
     * @return BufferedImage 实例，转换失败返回 null
     */
    private static java.awt.image.BufferedImage frameToBufferedImage(Frame frame) {
        if (frame.image == null || frame.image.length == 0) {
            return null;
        }
        Object image = frame.image[0];
        if (image instanceof java.awt.image.BufferedImage bi) {
            return bi;
        }
        if (image instanceof ByteBuffer buffer) {
            int w = frame.imageWidth;
            int h = frame.imageHeight;
            if (w <= 0 || h <= 0) {
                return null;
            }
            BufferedImage bgr = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            buffer.rewind();
            byte[] pixels = new byte[buffer.remaining()];
            buffer.get(pixels);
            bgr.getRaster().setDataElements(0, 0, w, h, pixels);
            return bgr;
        }
        return null;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void close() {
        initialized = false;
        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception ignored) {
            }
            try {
                grabber.release();
            } catch (Exception ignored) {
            }
            grabber = null;
            log.info("[JavaCVScreenCapture] 已关闭");
        }
    }
}
