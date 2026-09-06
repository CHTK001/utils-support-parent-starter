package com.chua.remote.controller;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

/**
 * 解码渲染器。
 *
 * <p>将网关转发的媒体数据帧解码为画面并渲染到本地窗口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DecoderRenderer {

    /** 解码能力 */
    private final CodecProfile decodingCapability;

    /** 是否启用硬件解码 */
    private final boolean hardwareDecode;

    public DecoderRenderer(CodecProfile decodingCapability) {
        this.decodingCapability = decodingCapability;
        this.hardwareDecode = true;
    }

    /**
     * 渲染数据帧。
     *
     * @param frame 数据帧
     */
    public void render(Frame frame) {
        if (frame.getType() != MessageType.DATA) {
            log.warn("非数据帧，无法渲染: type={}", frame.getType());
            return;
        }
        byte[] data = frame.getPayload();
        // 1. 解码
        byte[] decoded = decode(data);
        // 2. 渲染
        renderFrame(decoded);
    }

    /**
     * 解码数据。
     *
     * @param encoded 编码后的数据
     * @return 解码后的像素数据
     */
    private byte[] decode(byte[] encoded) {
        log.debug("解码数据: size={}", encoded.length);
        return new byte[0];
    }

    /**
     * 渲染帧到本地窗口。
     *
     * @param pixels 像素数据
     */
    private void renderFrame(byte[] pixels) {
        log.debug("渲染帧: size={}", pixels.length);
    }

    /**
     * 渲染缩略图。
     *
     * @param data 原始数据
     */
    public void renderThumbnail(byte[] data) {
        if (decodingCapability.isThumbnailSupported()) {
            log.debug("渲染缩略图");
        }
    }
}
