package com.chua.remote.support.gateway.core.codec;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编码帧 — 经过 Codec 编码后的视频帧数据
 * <p>包含编码格式、分辨率、时间戳和编码数据。由 Agent 端编码后发送，
 * 经网关透传到达前端后由 {@link CodecEngine} 解码为原始帧。
 *
 * @author CH
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncodedFrame {
    /** 编码器名称（如 "H264"、"H265"、"JPEG"） */
    private String codecName;
    /** 帧宽度（像素） */
    private int width;
    /** 帧高度（像素） */
    private int height;
    /** 编码后的二进制数据 */
    /**
     * 数据
     */
    private byte[] data;
    /** 帧时间戳（毫秒） */
    private long timestamp;
    /** 是否为关键帧（I 帧），解码器依赖关键帧初始化 */
    private boolean keyFrame;
}
