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

    /** 帧渲染回调（解码结果直接交付，嵌入方自行绘制到窗口） */
    private volatile java.util.function.Consumer<BufferedImage> frameListener;

    public DecoderRenderer(CodecProfile decodingCapability) {
        this.decodingCapability = decodingCapability;
        this.hardwareDecode = true;
        this.thumbnailRenderer = new ThumbnailRenderer(decodingCapability);
    }

    /**
     * 设置帧渲染回调。
     *
     * <p>解码结果（BufferedImage）直接交付回调，由嵌入方绘制到本地窗口；
     * 未设置时仅记录日志。热路径不做任何再编码。</p>
     *
     * @param listener 渲染回调
     */
    public void setFrameListener(java.util.function.Consumer<BufferedImage> listener) {
        this.frameListener = listener;
    }

    /**
     * 渲染数据帧。
     *
     * <p>热路径仅做一次解码：解码结果交付渲染回调；
     * 缩略图（若启用）直接由解码结果缩放生成，不再二次解码。</p>
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
            // 1. 解码（热路径唯一一次图像编解码）
            BufferedImage image = decode(data);
            if (image == null) {
                return;
            }
            // 2. 交付渲染回调
            renderFrame(image);
            // 3. 如果支持缩略图，由解码结果直接缩放生成
            if (decodingCapability.isThumbnailSupported()) {
                renderThumbnail(image);
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
     * 交付渲染帧。
     *
     * @param image 解码后的像素数据
     */
    private void renderFrame(BufferedImage image) {
        java.util.function.Consumer<BufferedImage> listener = frameListener;
        if (listener != null) {
            listener.accept(image);
        }
        log.debug("渲染帧: width={}, height={}", image.getWidth(), image.getHeight());
    }

    /**
     * 渲染缩略图（由解码结果直接缩放，不二次解码）。
     *
     * @param image 解码后的像素数据
     */
    private void renderThumbnail(BufferedImage image) {
        try {
            BufferedImage scaled = BufferedImageUtils.scaleImage(image,
                    Math.max(1, decodingCapability.getMaxWidth() / 4),
                    Math.max(1, decodingCapability.getMaxHeight() / 4));
            byte[] thumbnail = ImageProcessors.from(
                            BufferedImageUtils.toBufferedImageArray(scaled, "png"))
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
