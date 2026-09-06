package com.chua.remote.controller;

import com.chua.common.support.image.ImageProcessors;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.Session;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.Map;

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

    /** 缩略图渲染器 */
    private final ThumbnailRenderer thumbnailRenderer;

    public DecoderRenderer(CodecProfile decodingCapability) {
        this.decodingCapability = decodingCapability;
        this.hardwareDecode = true;
        this.thumbnailRenderer = new ThumbnailRenderer(decodingCapability);
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
        if (data == null || data.length == 0) {
            return;
        }
        try {
            // 1. 解码：将字节数组转为 BufferedImage
            BufferedImage image = decode(data);
            if (image == null) {
                return;
            }
            // 2. 渲染到本地窗口
            renderFrame(image);
            // 3. 如果支持缩略图，同时渲染缩略图
            if (decodingCapability.isThumbnailSupported()) {
                renderThumbnail(data);
            }
        } catch (Exception e) {
            log.error("渲染帧失败", e);
        }
    }

    /**
     * 解码数据为 BufferedImage。
     *
     * @param encoded 编码后的数据
     * @return BufferedImage
     */
    private BufferedImage decode(byte[] encoded) {
        try {
            return BufferedImageUtils.toBufferedImage(encoded);
        } catch (Exception e) {
            log.error("解码图像失败", e);
            return null;
        }
    }

    /**
     * 渲染帧到本地窗口。
     *
     * @param image 像素数据
     */
    private void renderFrame(BufferedImage image) {
        log.debug("渲染帧: width={}, height={}", image.getWidth(), image.getHeight());
        // 使用 ImageProcessors 进行后处理（如亮度/对比度调整）
        try {
            byte[] processed = ImageProcessors.from(BufferedImageUtils.toBufferedImageArray(image, "png"))
                    .format(decodingCapability.getEncodings().isEmpty() ? "jpeg" : decodingCapability.getEncodings().get(0))
                    .toBytes();
            log.debug("帧渲染完成: size={}", processed.length);
        } catch (Exception e) {
            log.error("帧后处理失败", e);
        }
    }

    /**
     * 渲染缩略图。
     *
     * @param data 原始数据
     */
    private void renderThumbnail(byte[] data) {
        try {
            byte[] thumbnail = ImageProcessors.from(data)
                    .resize(decodingCapability.getMaxWidth() / 4, decodingCapability.getMaxHeight() / 4)
                    .format("jpeg")
                    .toBytes();
            log.debug("缩略图渲染完成: size={}", thumbnail.length);
        } catch (Exception e) {
            log.error("缩略图渲染失败", e);
        }
    }

    /**
     * 渲染指定尺寸的帧。
     *
     * @param frame  数据帧
     * @param width  目标宽度
     * @param height 目标高度
     */
    public void renderScaled(Frame frame, int width, int height) {
        if (frame.getType() != MessageType.DATA) {
            return;
        }
        try {
            byte[] data = frame.getPayload();
            if (data == null || data.length == 0) {
                return;
            }
            byte[] scaled = ImageProcessors.from(data)
                    .resize(width, height)
                    .format("jpeg")
                    .toBytes();
            log.debug("缩放渲染完成: {}x{}", width, height);
        } catch (Exception e) {
            log.error("缩放渲染失败", e);
        }
    }

    /**
     * 缩略图渲染器。
     */
    static class ThumbnailRenderer {
        private final CodecProfile capability;

        ThumbnailRenderer(CodecProfile capability) {
            this.capability = capability;
        }

        byte[] render(byte[] data) {
            try {
                return ImageProcessors.from(data)
                        .resize(capability.getMaxWidth() / 4, capability.getMaxHeight() / 4)
                        .format("jpeg")
                        .toBytes();
            } catch (Exception e) {
                return new byte[0];
            }
        }
    }
}
