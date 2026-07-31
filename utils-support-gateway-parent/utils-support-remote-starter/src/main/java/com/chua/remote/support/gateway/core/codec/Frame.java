package com.chua.remote.support.gateway.core.codec;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 原始帧 — 解码后的未压缩视频帧
 * <p>包含像素数据和格式信息。由 {@link CodecEngine} 解码后产生，
 * 可用于后续处理或转发。
 *
 * @author CH
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Frame {
    /** 帧宽度（像素） */
    private int width;
    /** 帧高度（像素） */
    private int height;
    /** 原始像素数据 */
    /**
     * 数据
     */
    private byte[] data;
    /** 像素格式 */
    /**
     * 格式
     */
    private FrameFormat format;

    /** 原始帧像素格式枚举 */
    public enum FrameFormat { RGB, YUV_420, YUV_422, RAW }
}
