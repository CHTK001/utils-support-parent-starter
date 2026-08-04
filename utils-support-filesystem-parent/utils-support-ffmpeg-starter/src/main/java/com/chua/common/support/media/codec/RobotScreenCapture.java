package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacv.Frame;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;

import static org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;

/**
 * 基于 java.awt.Robot 的纯 Java 屏幕采集实现。
 *
 * <p>无需任何 native 依赖，跨平台支持 Windows / Linux / macOS。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("robot")
public class RobotScreenCapture implements ScreenCature {

    private Robot robot;
    private int width;
    private int height;
    private int fps;
    private boolean initialized;
    private SwsContext swsCtx;
    private ByteBuffer yuvBuf;

    @Override
    public boolean init(int width, int height, int fps) {
        try {
            this.robot = new Robot();
            this.width = width;
            this.height = height;
            this.fps = fps;
            int ySize = width * height;
            int uvSize = (width / 2) * (height / 2);
            this.yuvBuf = ByteBuffer.allocateDirect(ySize + uvSize * 2);
            this.initialized = true;
            log.info("[RobotScreenCapture] 已初始化: {}x{} {}fps YUV420P", width, height, fps);
            return true;
        } catch (AWTException e) {
            log.error("[RobotScreenCapture] 初始化失败: {}", e.getMessage());
            this.initialized = false;
            return false;
        }
    }

    @Override
    public Frame grabFrame() {
        if (!initialized || robot == null) {
            log.warn("[RobotScreenCapture] 未初始化，无法采集");
            return null;
        }
        try {
            BufferedImage fullScreen = robot.createScreenCapture(
                    new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            scaled.getGraphics().drawImage(fullScreen, 0, 0, width, height, null);
            byte[] pixels = ((DataBufferByte) scaled.getRaster().getDataBuffer()).getData();

            BytePointer srcData = new BytePointer(pixels);
            int[] srcStride = new int[]{width * 3};

            org.bytedeco.ffmpeg.avutil.AVFrame tmpFrame = org.bytedeco.ffmpeg.global.avutil.av_frame_alloc();
            int size = org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size(
                    avutil.AV_PIX_FMT_YUV420P, width, height, 1);
            BytePointer tmpBuf = new BytePointer(org.bytedeco.ffmpeg.global.avutil.av_malloc(size));
            org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays(
                    new PointerPointer(tmpFrame), tmpFrame.linesize(), tmpBuf,
                    avutil.AV_PIX_FMT_YUV420P, width, height, 1);
            tmpFrame.format(avutil.AV_PIX_FMT_YUV420P);
            tmpFrame.width(width);
            tmpFrame.height(height);

            SwsContext sws = sws_getCachedContext(swsCtx, width, height, avutil.AV_PIX_FMT_BGR24,
                    width, height, avutil.AV_PIX_FMT_YUV420P, SWS_BILINEAR, null, null, (double[]) null);
            this.swsCtx = sws;

            sws_scale(sws, new PointerPointer(srcData), new IntPointer(srcStride),
                    0, height, new PointerPointer(tmpFrame), tmpFrame.linesize());

            int ySize = width * height;
            int uvSize = (width / 2) * (height / 2);
            yuvBuf.clear();
            byte[] plane = new byte[Math.max(ySize, uvSize)];
            new BytePointer(tmpFrame.data(0)).get(plane, 0, ySize);
            yuvBuf.put(plane, 0, ySize);
            new BytePointer(tmpFrame.data(1)).get(plane, 0, uvSize);
            yuvBuf.put(plane, 0, uvSize);
            new BytePointer(tmpFrame.data(2)).get(plane, 0, uvSize);
            yuvBuf.put(plane, 0, uvSize);
            yuvBuf.flip();

            org.bytedeco.ffmpeg.global.avutil.av_frame_free(tmpFrame);
            org.bytedeco.ffmpeg.global.avutil.av_free(tmpBuf);

            Frame result = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
            result.imageWidth = width;
            result.imageHeight = height;
            result.imageStride = width;
            yuvBuf.position(0).limit(ySize);
            result.image[0] = yuvBuf.slice();
            yuvBuf.position(ySize).limit(ySize + uvSize);
            result.image[1] = yuvBuf.slice();
            yuvBuf.position(ySize + uvSize).limit(ySize + uvSize * 2);
            result.image[2] = yuvBuf.slice();
            return result;
        } catch (Exception e) {
            log.warn("[RobotScreenCapture] 采集帧失败: {}", e.getMessage());
            return null;
        }
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
        this.initialized = false;
        this.robot = null;
        if (swsCtx != null) {
            org.bytedeco.ffmpeg.global.swscale.sws_freeContext(swsCtx);
            swsCtx = null;
        }
        log.info("[RobotScreenCapture] 已关闭");
    }
}