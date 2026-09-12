package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.ffmpeg.support.codec.EncodesFrame;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/**
* 基于 javacv ffmpeg 的 H.266/VVC 编码器。
*
* <p>注意：当前 FFmpeg 版本默认可能不支持 H.266 编码，若不可用请改用 H.265 编码器。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"h266", "vvc"}, order = 35)
public class H266VideoEncoder implements VideoEncoder, EncodesFrame {
    /** Codec_名称_h266 */
    private static final String CODEC_NAME_H266 = "h266";
    /** 格式化_h266 */
    private static final String FORMAT_H266 = "h266";
    /** 内存_流_initial_容量 */
    private static final int MEMORY_STREAM_INITIAL_CAPACITY = 64 * 1024;
    /** Gop_大小 */
    private static final int GOP_SIZE = 150;
    /** 键_preset */
    private static final String KEY_PRESET = "preset";
    /** 键_tune */
    private static final String KEY_TUNE = "tune";
    /** 键_配置文件 */
    private static final String KEY_PROFILE = "profile";
    /** Val_preset */
    private static final String VAL_PRESET = "ultrafast";
    /** Val_tune */
    private static final String VAL_TUNE = "zerolatency";
    /** Val_配置文件 */
    private static final String VAL_PROFILE = "main";

    /** Recorder */
    private FFmpegFrameRecorder recorder;
    /** 内存流 */
    private ByteArrayOutputStream memoryStream;
    /** 宽度 */
    private int width;
    /** 高度 */
    private int height;
    /** FPS */
    private int fps;
    /** 密钥framerequested */
    private boolean keyFrameRequested;
    /** PTS */
    private long pts;
    /** 启动 */
    private boolean started;
    /** 帧索引 */
    private long frameIndex;
    /** 缓冲图片转换器 */
    private Java2DFrameConverter bufferedImageConverter;

    /** 创建 H266视频编码器 实例 */
    public H266VideoEncoder() {
    }

    /**
    * 创建 H266视频编码器 实例
    * @param width width
    * @param width int
    * @param width int
    * @param height height
    * @param fps fps
     */
    public H266VideoEncoder(int width, int height, int fps) {
        init(width, height, fps);
    }

    /**
    * 创建 H266视频编码器 实例
    * @param width width
    * @param width Integer
    * @param width Integer
    * @param height height
    * @param fps fps
     */
    public H266VideoEncoder(Integer width, Integer height, Integer fps) {
        if (width != null && height != null && fps != null) {
            init(width, height, fps);
        }
    }

    /**
    * 创建 H266视频编码器 实例
    * @param args 参数
     */
    public H266VideoEncoder(Object... args) {
        if (args != null && args.length >= 3
                && args[0] instanceof Number
                && args[1] instanceof Number
                && args[2] instanceof Number) {
            init(((Number) args[0]).intValue(),
                    ((Number) args[1]).intValue(),
                    ((Number) args[2]).intValue());
        }
    }

    /**
    * 初始化
    *
    * @param width width
    * @param height height
    * @param fps fps
    * @return 初始化的结果
     */
    public boolean init(int width, int height, int fps) {
        close();
        this.width = ensureEven(width);
        this.height = ensureEven(height);
        this.fps = Math.max(1, fps);
        this.memoryStream = new ByteArrayOutputStream(MEMORY_STREAM_INITIAL_CAPACITY);
        try {
            this.recorder = new FFmpegFrameRecorder(new OutputStream() {
                @Override public void write(int b) { memoryStream.write(b); }
                @Override public void write(byte[] b, int off, int len) { memoryStream.write(b, off, len); }
            }, this.width, this.height);
            this.recorder.setFormat(FORMAT_H266);
            this.recorder.setVideoCodec(avcodec.AV_CODEC_ID_H266);
            this.recorder.setFrameRate(this.fps);
            this.recorder.setVideoOption(KEY_PRESET, VAL_PRESET);
            this.recorder.setVideoOption(KEY_TUNE, VAL_TUNE);
            this.recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            this.recorder.setVideoQuality(0);
            this.recorder.setInterleaved(true);
            this.recorder.setGopSize(GOP_SIZE);
            this.recorder.start();
            this.started = true;
            this.bufferedImageConverter = new Java2DFrameConverter();
            log.info("[H266VideoEncoder] 已启动: {}x{} {}fps (H266/VVC 内存编码)", this.width, this.height, this.fps);
        } catch (Exception e) {
            log.error("[H266VideoEncoder] 初始化失败: {}", e.getMessage(), e);
            this.recorder = null;
            this.started = false;
        }
        return this.started;
    }

    /**
    * ensureeven
    *
    * @param v v
    * @return ensureEven的结果
     */
    private static int ensureEven(int v) {
        return v + (v & 1);
    }

    @Override
    /** 获取codec名称 */
    public String getCodecName() {
        return CODEC_NAME_H266;
    }

    @Override
    /** 获取codecid */
    public int getCodecId() {
        return avcodec.AV_CODEC_ID_H266;
    }

    @Override
    /** 是否hardware加速 */
    public boolean isHardwareAccelerated() {
        return false;
    }

    @Override
    /** force键帧 */
    public void forceKeyFrame() {
        this.keyFrameRequested = true;
    }

    /**
    * ensure初始化
    *
    * @param w w
    * @param h h
    * @param f f
     */
    private void ensureInitialized(int w, int h, int f) {
        if (!started || recorder == null) {
            init(w, h, f);
        }
    }

    @Override
    /** 编码 */
    public byte[] encode(BufferedImage image) {
        if (image == null) {
            return new byte[0];
        }
        ensureInitialized(image.getWidth(), image.getHeight(), 30);
        if (!started || recorder == null) {
            return new byte[0];
        }
        try {
            BufferedImage bgr = ensureBgr(image);
            if (bufferedImageConverter == null) {
                bufferedImageConverter = new Java2DFrameConverter();
            }
            Frame frame = bufferedImageConverter.convert(bgr);
            if (frame == null) {
                return new byte[0];
            }
            return encodeInternal(frame);
        } catch (Throwable e) {
            log.warn("[H266VideoEncoder] encode(BufferedImage) 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    /** 编码 */
    public byte[] encode(Frame frame) {
        if (frame == null) {
            return new byte[0];
        }
        ensureInitialized(Math.max(frame.imageWidth, 640), Math.max(frame.imageHeight, 480), 30);
        if (!started || recorder == null) {
            return new byte[0];
        }
        try {
            return encodeInternal(frame);
        } catch (Throwable e) {
            log.warn("[H266VideoEncoder] encode(Frame) 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
    * 编码内部
    *
    * @param frame 帧
    * @return encode内部的结果
     */
    private byte[] encodeInternal(Frame frame) {
        if (!started || recorder == null) {
            return new byte[0];
        }
        try {
            if (keyFrameRequested) {
                recorder.setVideoOption(KEY_FORCE_KEY_FRAME, "1");
                keyFrameRequested = false;
            }
            recorder.record(frame);
            frameIndex++;
            pts = frameIndex * 90000 / Math.max(1, fps);
            recorder.setTimestamp(pts);
            byte[] data = memoryStream.toByteArray();
            memoryStream.reset();
            return data;
        } catch (Throwable e) {
            log.warn("[H266VideoEncoder] encodeInternal 失败: {}", e.getMessage());
            started = false;
            return new byte[0];
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        started = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        if (memoryStream != null) {
            try {
                memoryStream.close();
            } catch (Exception ignored) {
            }
            memoryStream = null;
        }
        if (bufferedImageConverter != null) {
            try {
                bufferedImageConverter.close();
            } catch (Exception ignored) {
            }
            bufferedImageConverter = null;
        }
        width = 0;
        height = 0;
        fps = 0;
        pts = 0;
        frameIndex = 0;
        keyFrameRequested = false;
    }

    /**
    * ensurebgr
    *
    * @param src src
    * @return ensureBgr的结果
     */
    private static BufferedImage ensureBgr(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return src;
        }
        BufferedImage bgr = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        bgr.getGraphics().drawImage(src, 0, 0, null);
        return bgr;
    }

    /** 键_force_键_帧 */
    private static final String KEY_FORCE_KEY_FRAME = "force_key_frame";
}
