package com.chua.remote.controller;

import com.chua.common.support.image.ImageProcessors;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.nativevideocodec.support.NativeVideoCodec;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Map;

@Slf4j
public class DecoderRenderer {

    private final CodecProfile decodingCapability;
    private final boolean hardwareDecode;
    private final ThumbnailRenderer thumbnailRenderer;
    private volatile java.util.function.Consumer<BufferedImage> frameListener;
    private long h264DecoderHandle;
    private int lastWidth;
    private int lastHeight;

    public DecoderRenderer(CodecProfile decodingCapability) {
        this.decodingCapability = decodingCapability;
        this.hardwareDecode = true;
        this.thumbnailRenderer = new ThumbnailRenderer(decodingCapability);
    }

    public void setFrameListener(java.util.function.Consumer<BufferedImage> listener) {
        this.frameListener = listener;
    }

    public void render(Frame frame) {
        if (frame.getType() != MessageType.DATA) {
            return;
        }
        byte[] data = frame.getPayload();
        if (data == null || data.length == 0) {
            return;
        }
        try {
            BufferedImage image = decode(data);
            if (image == null) {
                return;
            }
            renderFrame(image);
            if (decodingCapability.isThumbnailSupported()) {
                renderThumbnail(image);
            }
        } catch (Exception e) {
            log.error("渲染帧失败", e);
        }
    }

    private BufferedImage decode(byte[] encoded) {
        if (isH264(encoded)) {
            return decodeH264(encoded);
        }
        return decodeJpeg(encoded);
    }

    private boolean isH264(byte[] data) {
        if (data.length < 4) {
            return false;
        }
        return (data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x01)
                || (data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x01);
    }

    private BufferedImage decodeH264(byte[] h264Data) {
        try {
            int width = decodingCapability.getMaxWidth();
            int height = decodingCapability.getMaxHeight();
            if (h264DecoderHandle == 0 || lastWidth != width || lastHeight != height) {
                if (h264DecoderHandle != 0) {
                    NativeVideoCodec.h264DecoderFree(h264DecoderHandle);
                }
                h264DecoderHandle = NativeVideoCodec.h264DecoderCreate(width, height);
                lastWidth = width;
                lastHeight = height;
            }
            byte[] bgr = NativeVideoCodec.h264Decode(h264DecoderHandle, h264Data, h264Data.length);
            if (bgr == null || bgr.length == 0) {
                return null;
            }
            return bgrToBufferedImage(bgr, width, height);
        } catch (Exception e) {
            log.error("H264解码失败", e);
            return null;
        }
    }

    private BufferedImage bgrToBufferedImage(byte[] bgr, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(bgr, 0, pixels, 0, Math.min(bgr.length, pixels.length));
        return image;
    }

    private BufferedImage decodeJpeg(byte[] encoded) {
        try {
            return BufferedImageUtils.toBufferedImage(encoded);
        } catch (Exception e) {
            log.error("JPEG解码失败", e);
            return null;
        }
    }

    private void renderFrame(BufferedImage image) {
        java.util.function.Consumer<BufferedImage> listener = frameListener;
        if (listener != null) {
            listener.accept(image);
        }
        log.debug("渲染帧: width={}, height={}", image.getWidth(), image.getHeight());
    }

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

    public void dispose() {
        if (h264DecoderHandle != 0) {
            NativeVideoCodec.h264DecoderFree(h264DecoderHandle);
            h264DecoderHandle = 0;
        }
    }

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
