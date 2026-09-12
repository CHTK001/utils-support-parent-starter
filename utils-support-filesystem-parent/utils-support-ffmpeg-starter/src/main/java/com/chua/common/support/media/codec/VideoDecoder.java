package com.chua.common.support.media.codec;

import java.nio.ByteBuffer;

/**
* 视频解码器接口。
*
* @author CH
* @since 4.0.0.42
 */
public interface VideoDecoder {

    /**
    * 使用配置初始化解码器。
    *
    * @param codecId ffmpeg 编解码器标识（例如 AV_CODEC_标识_H264）
    * @param width 帧宽度
    * @param height 帧高度
    * @return 初始化成功返回 true
     */
    boolean init(int codecId, int width, int height);

    /**
    * 将单个编码数据包解码为原始视频帧。
    *
    * @param packet 编码数据
    * @return 解码后的 ARGB byte缓冲，解码失败返回 空
     */
    ByteBuffer decode(byte[] packet);

    /**
    * 刷新解码器缓冲区（排空剩余帧）。
    *
    * @return 剩余解码帧数组，无帧时返回空数组
     */
    ByteBuffer[] flush();

    /**
    * 获取当前输出宽度。
    *
    * @return 输出宽度
     */
    int getWidth();

    /**
    * 获取当前输出高度。
    *
    * @return 输出高度
     */
    int getHeight();

    /**
    * 释放解码器资源。
     */
    void close();
}
