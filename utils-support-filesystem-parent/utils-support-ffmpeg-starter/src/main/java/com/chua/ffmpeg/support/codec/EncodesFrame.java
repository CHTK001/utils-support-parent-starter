package com.chua.ffmpeg.support.codec;

import org.bytedeco.javacv.Frame;

/**
 * 标记接口 — 标记可直接接受 Frame 进行零拷贝编码的编码器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface EncodesFrame {
    /**
     * 直接编码 Frame（零拷贝路径）。
     *
     * @param frame 输入帧
     * @return 编码后的字节数组
     */
    byte[] encode(Frame frame);
}
