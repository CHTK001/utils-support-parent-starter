package com.chua.ffmpeg.rust.support.bridge;

import com.chua.nativeffmpeg.support.NativeFFmpeg;
import com.chua.nativevideocodec.support.NativeVideoCodec;
import com.chua.common.support.media.ffmpeg.FrameInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
* rustffmpeg处理器 / 视频编码器 ??? cdylib ????????
*
* <p>??????? native-video-codec ? native-crypto ??????</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public final class RustFFmpegBridge {

    /** ?? rustffmpegbridge ?? */
    private RustFFmpegBridge() {
    }

    /**
    * ???????????
    *
    * @return true ?????
    */
    public static boolean isLoaded() {
        try {
            NativeVideoCodec.getVersion();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
    * ??????????
    *
    * @return ??????????? 空
    */
    public static Throwable getLoadError() {
        try {
            NativeVideoCodec.getVersion();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    /**
    * ???????????
    *
    * @return ?????
    */
    public static String getVersion() {
        return "native-video-codec-" + NativeVideoCodec.getVersion();
    }

    /**
    * H.264 ?????
    *
    * @param bgr24 BGR24 ??????
    * @param width ????
    * @param height ????
    * @param fps ??
    * @return ????????
    */
    public static byte[] h264Encode(byte[] bgr24, int width, int height, int fps) {
        long encoder = NativeVideoCodec.h264EncoderCreate(width, height, Math.max(1, fps), 23, 1, 1);
        if (encoder == 0) {
            return new byte[0];
        }
        try {
            return NativeVideoCodec.h264Encode(encoder, bgr24, width, height);
        } finally {
            NativeVideoCodec.h264EncoderFree(encoder);
        }
    }

    /**
    * H.265 ?????
    *
    * @param bgr24 BGR24 ??????
    * @param width ????
    * @param height ????
    * @param fps ??
    * @return ????????
    */
    public static byte[] h265Encode(byte[] bgr24, int width, int height, int fps) {
        long encoder = NativeVideoCodec.h265EncoderCreate(width, height, Math.max(1, fps), 23, 1, 1);
        if (encoder == 0) {
            return new byte[0];
        }
        try {
            return NativeVideoCodec.h265Encode(encoder, bgr24, width, height);
        } finally {
            NativeVideoCodec.h265EncoderFree(encoder);
        }
    }

    /**
    * H.266 ?????
    *
    * @param bgr24 BGR24 ??????
    * @param width ????
    * @param height ????
    * @param fps ??
    * @return ????????
    */
    public static byte[] h266Encode(byte[] bgr24, int width, int height, int fps) {
        long encoder = NativeVideoCodec.h266EncoderCreate(width, height, Math.max(1, fps), 23, 1, 1);
        if (encoder == 0) {
            return new byte[0];
        }
        try {
            return NativeVideoCodec.h266Encode(encoder, bgr24, width, height);
        } finally {
            NativeVideoCodec.h266EncoderFree(encoder);
        }
    }

    /**
    * H.264 ???????
    *
    * @param packet ?????
    * @param width ????
    * @param height ????
    * @return ????????
    */
    public static byte[] h264Decode(byte[] packet, int width, int height) {
        long decoder = NativeVideoCodec.h264DecoderCreate(width, height);
        if (decoder == 0) {
            return new byte[0];
        }
        try {
            byte[] decoded = NativeVideoCodec.h264Decode(decoder, packet, packet == null ? 0 : packet.length);
            return decoded == null ? new byte[0] : decoded;
        } finally {
            NativeVideoCodec.h264DecoderFree(decoder);
        }
    }

    /**
    * H.265 ???????
    *
    * @param packet ?????
    * @param width ????
    * @param height ????
    * @return ????????
    */
    public static byte[] h265Decode(byte[] packet, int width, int height) {
        long decoder = NativeVideoCodec.h265DecoderCreate(width, height);
        if (decoder == 0) {
            return new byte[0];
        }
        try {
            byte[] decoded = NativeVideoCodec.h265Decode(decoder, packet, packet == null ? 0 : packet.length);
            return decoded == null ? new byte[0] : decoded;
        } finally {
            NativeVideoCodec.h265DecoderFree(decoder);
        }
    }

    /**
    * H.266 ???????
    *
    * @param packet ?????
    * @param width ????
    * @param height ????
    * @return ????????
    */
    public static byte[] h266Decode(byte[] packet, int width, int height) {
        long decoder = NativeVideoCodec.h266DecoderCreate(width, height);
        if (decoder == 0) {
            return new byte[0];
        }
        try {
            byte[] decoded = NativeVideoCodec.h266Decode(decoder, packet, packet == null ? 0 : packet.length);
            return decoded == null ? new byte[0] : decoded;
        } finally {
            NativeVideoCodec.h266DecoderFree(decoder);
        }
    }

    // ==================== ??/???? ====================

    /**
    * ?? natffmpeg ??/?????????
    *
    * @return true ?????
    */
    public static boolean isStreamLoaded() {
        return NativeFFmpeg.isLoaded();
    }

    /**
    * RTMP ????????
    *
    * @param inputUrl   ?? URL ?????
    * @param streamUrl  ????
    * @param videoCodec ????????空 ????
    * @param audioCodec ????????空 ????
    * @param width      ?????0 ???
    * @param height     ?????0 ???
    * @param fps        ???0 ???
    * @return 0 ????
    */
    public static int pushStream(String inputUrl, String streamUrl,
                                  String videoCodec, String audioCodec,
                                  int width, int height, int fps) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.pushStream(inputUrl, streamUrl, videoCodec, audioCodec, width, height, fps);
    }

    /**
    * RTMP ???????????
    *
    * @param inputUrl   ?? URL ?????
    * @param streamUrl  ????
    * @param videoCodec ????????空 ????
    * @param audioCodec ????????空 ????
    * @param width      ?????0 ???
    * @param height     ?????0 ???
    * @param fps        ???0 ???
    * @param callback   ?????
    * @return 0 ????
    */
    public static int pushStreamWithCallback(String inputUrl, String streamUrl,
                                              String videoCodec, String audioCodec,
                                              int width, int height, int fps,
                                              Consumer<FrameInfo> callback) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        NativeFFmpeg.FrameCallback jniCallback = (frameNumber, timestampMs, w, h, codec, f, keyFrame) -> {
            if (callback != null) {
                FrameInfo info = new FrameInfo();
                info.setFrameNumber(frameNumber);
                info.setTimestampMs(timestampMs);
                info.setWidth(w);
                info.setHeight(h);
                info.setCodec(codec);
                info.setFps(f);
                info.setKeyFrame(keyFrame);
                callback.accept(info);
            }
        };
        return NativeFFmpeg.pushStreamWithCallback(inputUrl, streamUrl, videoCodec, audioCodec,
                width, height, fps, jniCallback);
    }

    /**
    * RTMP ??????????
    *
    * @param streamUrl  ????
    * @param outputPath ??????
    * @param duration   ????????0 ??????
    * @return 0 ????
    */
    public static int pullStream(String streamUrl, String outputPath, double duration) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.pullStream(streamUrl, outputPath, duration);
    }

    /**
    * RTMP ?????????????
    *
    * @param streamUrl  ????
    * @param outputPath ??????
    * @param duration   ????????0 ??????
    * @param callback   ?????
    * @return 0 ????
    */
    public static int pullStreamWithCallback(String streamUrl, String outputPath,
                                              double duration,
                                              Consumer<FrameInfo> callback) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        NativeFFmpeg.FrameCallback jniCallback = (frameNumber, timestampMs, w, h, codec, f, keyFrame) -> {
            if (callback != null) {
                FrameInfo info = new FrameInfo();
                info.setFrameNumber(frameNumber);
                info.setTimestampMs(timestampMs);
                info.setWidth(w);
                info.setHeight(h);
                info.setCodec(codec);
                info.setFps(f);
                info.setKeyFrame(keyFrame);
                callback.accept(info);
            }
        };
        return NativeFFmpeg.pullStreamWithCallback(streamUrl, outputPath, duration, jniCallback);
    }

    /**
    * ?????????
    *
    * @param inputUrl ?? URL ?????
    * @return ?????????? -1
    */
    public static double getStreamDuration(String inputUrl) {
        if (!NativeFFmpeg.isLoaded()) {
            return -1;
        }
        return NativeFFmpeg.getDuration(inputUrl);
    }

    // ==================== ?????? ====================

    /**
    * ???????
    *
    * @param inputUrl    ??????
    * @param outputPath  ??????
    * @param videoCodec  ??????空 ????
    * @param audioCodec  ??????空 ????
    * @param width       ?????0 ???
    * @param height      ?????0 ???
    * @param fps         ???0 ???
    * @param startTime   ????????0 ????
    * @param duration    ????????0 ???
    * @param removeVideo ???????
    * @param removeAudio ???????
    * @return 0 ????
    */
    public static int convertFile(String inputUrl, String outputPath,
                                   String videoCodec, String audioCodec,
                                   int width, int height, int fps,
                                   double startTime, double duration,
                                   boolean removeVideo, boolean removeAudio) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.convertFile(inputUrl, outputPath, videoCodec, audioCodec,
                width, height, fps, startTime, duration, removeVideo, removeAudio);
    }

    /**
    * ??????????
    *
    * @param inputUrl   ??????
    * @param timestampMs ???????
    * @param outputPath  ??????
    * @return 0 ????
    */
    public static int captureFrame(String inputUrl, long timestampMs, String outputPath) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.captureFrame(inputUrl, timestampMs, outputPath);
    }

    /**
    * ?????????
    *
    * @param inputPaths ??????????????
    * @param outputPath ??????
    * @return 0 ????
    */
    public static int concatFiles(String inputPaths, String outputPath) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.concatFiles(inputPaths, outputPath);
    }

    /**
    * ?????????JSON ????
    *
    * @param inputUrl ?? URL ?????
    * @return JSON ???????????? 空
    */
    public static String getStreamMediaInfo(String inputUrl) {
        if (!NativeFFmpeg.isLoaded()) {
            return null;
        }
        return NativeFFmpeg.getMediaInfo(inputUrl);
    }

    // ==================== ?????? ====================

    /**
    * ?????90/180/270???
    *
    * @param inputUrl   ??????
    * @param outputPath ??????
    * @param angle      ?????90/180/270?
    * @return 0 ????
    */
    public static int rotate(String inputUrl, String outputPath, int angle) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.rotate(inputUrl, outputPath, angle);
    }

    // ==================== ?????? ====================

    /**
    * ???????
    *
    * @param inputUrl      ????????
    * @param watermarkPath ????????
    * @param outputPath    ??????
    * @param x             ?? X ??
    * @param y             ?? Y ??
    * @return 0 ????
    */
    public static int addWatermark(String inputUrl, String watermarkPath,
                                   String outputPath, int x, int y) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.addWatermark(inputUrl, watermarkPath, outputPath, x, y);
    }

    // ==================== ????????? ====================

    /**
    * ????????
    *
    * @param imageDir     ??????
    * @param outputPath   ????????
    * @param fps          ??
    * @param imagePattern ??????????? "帧_%06d.jpg"?
    * @return 0 ????
    */
    public static int imagesToVideo(String imageDir, String outputPath,
                                    int fps, String imagePattern) {
        if (!NativeFFmpeg.isLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded: " + NativeFFmpeg.getLoadError());
        }
        return NativeFFmpeg.imagesToVideo(imageDir, outputPath, fps, imagePattern);
    }
}

