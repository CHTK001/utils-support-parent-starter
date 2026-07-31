package com.chua.remote.support.gateway.core.codec;


/**
 * 编解码插件接口 — 支持可插拔的视频/图像编码格式
 * <p>实现类需注册到 {@link CodecEngine} 中。典型实现包括 H.264、H.265、JPEG 等。
 *
 * @author CH
 */
public interface CodecPlugin {
    /** 返回编解码器名称（如 "H264"、"JPEG"），用于注册和查找 */
    String codecName();

    /** 将编码帧解码为原始帧数据 */
    Frame decode(EncodedFrame encodedFrame);

    /** 将原始帧编码为编码帧数据 */
    EncodedFrame encode(Frame frame);
}
