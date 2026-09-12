package com.chua.ffmpeg.rust.support.codec;

import com.chua.common.support.media.codec.VideoDecoder;
import com.chua.nativevideocodec.support.NativeVideoCodec;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;

/**
 * 基于 Rust 后端的 H.264 / H.265 / H.266 解码器。
 *
 * <p>通过 {@link #init(int, int, int)} 的第一个参数 codecId 选择编解码器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RustVideoDecoder implements VideoDecoder {

    /**
     * 解码器原生句柄
     */
    private long decoderHandle;

    /**
     * 当前编解码器标识
     */
    private int codecId;

    /**
     * 视频宽度
     */
    private int width;

    /**
     * 视频高度
     */
    private int height;

    /**
     * 解码器是否已初始化
     */
    private boolean initialized;

    /**
      * ffmpeg AV_CODEC_标识_H264
     */
    private static final int AV_CODEC_ID_H264 = 27;

    /**
      * ffmpeg AV_CODEC_标识_H265
     */
    private static final int AV_CODEC_ID_H265 = 173;

    /**
      * ffmpeg AV_CODEC_标识_H266
     */
    private static final int AV_CODEC_ID_H266 = 276;

    @Override
    /** 初始化 */
    public synchronized boolean init(int codecId, int width, int height) {
        close();
        this.codecId = codecId;
        this.width = width;
        this.height = height;
        switch (codecId) {
            case AV_CODEC_ID_H264:
                this.decoderHandle = NativeVideoCodec.h264DecoderCreate(width, height);
                break;
            case AV_CODEC_ID_H265:
                this.decoderHandle = NativeVideoCodec.h265DecoderCreate(width, height);
                break;
            case AV_CODEC_ID_H266:
                this.decoderHandle = NativeVideoCodec.h266DecoderCreate(width, height);
                break;
            default:
                log.warn("[RustVideoDecoder] 不支持的 codecId: {}", codecId);
                return false;
        }
        this.initialized = this.decoderHandle != 0;
        log.info("[RustVideoDecoder] 初始化 codecId={} {}x{} handle={}", codecId, width, height, this.decoderHandle);
        return this.initialized;
    }

    @Override
    /** 解码 */
    public synchronized ByteBuffer decode(byte[] packet) {
        if (!initialized || decoderHandle == 0 || packet == null || packet.length == 0) {
            return null;
        }
        try {
            byte[] decoded;
            switch (codecId) {
                case AV_CODEC_ID_H264:
                    decoded = NativeVideoCodec.h264Decode(decoderHandle, packet, packet.length);
                    break;
                case AV_CODEC_ID_H265:
                    decoded = NativeVideoCodec.h265Decode(decoderHandle, packet, packet.length);
                    break;
                case AV_CODEC_ID_H266:
                    decoded = NativeVideoCodec.h266Decode(decoderHandle, packet, packet.length);
                    break;
                default:
                    return null;
            }
            if (decoded == null || decoded.length == 0) {
                return null;
            }
            return ByteBuffer.wrap(decoded);
        } catch (Throwable e) {
            log.warn("[RustVideoDecoder] decode 失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    /** 刷新 */
    public ByteBuffer[] flush() {
        return new ByteBuffer[0];
    }

    @Override
    /** 获取Width */
    public int getWidth() {
        return width;
    }

    @Override
    /** 获取Height */
    public int getHeight() {
        return height;
    }

    @Override
    /** 关闭 */
    public synchronized void close() {
        if (decoderHandle != 0) {
            switch (codecId) {
                case AV_CODEC_ID_H264:
                    NativeVideoCodec.h264DecoderFree(decoderHandle);
                    break;
                case AV_CODEC_ID_H265:
                    NativeVideoCodec.h265DecoderFree(decoderHandle);
                    break;
                case AV_CODEC_ID_H266:
                    NativeVideoCodec.h266DecoderFree(decoderHandle);
                    break;
            }
            decoderHandle = 0;
        }
        initialized = false;
    }
}
