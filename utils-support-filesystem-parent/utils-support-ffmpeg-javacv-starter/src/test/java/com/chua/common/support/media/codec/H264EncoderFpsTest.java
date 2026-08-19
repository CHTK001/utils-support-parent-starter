package com.chua.common.support.media.codec;

import com.chua.common.support.spi.ServiceProvider;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;

/** @author CH */
public class H264EncoderFpsTest {
    /** Main */
    public static void main(String[] args) throws Exception {
        int w = 1280, h = 720;
        String encoderName = args.length > 0 ? args[0] : "javacv-ffmpeg";
        int frameCount = args.length > 1 ? Integer.parseInt(args[1]) : 300;

        VideoEncoder encoder = ServiceProvider.of(VideoEncoder.class)
                .getNewExtension(encoderName, w, h, 30);
        if (encoder == null) {
            System.err.println("[FAIL] 未找到编码器: " + encoderName);
            System.exit(1);
        }

        System.out.println("=== H264 编码器真实 fps 测试 ===");
        System.out.println("编码器: " + encoder.getClass().getSimpleName()
                + ", name=" + encoder.getCodecName()
                + ", codecId=" + encoder.getCodecId()
                + ", hw=" + encoder.isHardwareAccelerated());
        System.out.println("配置: " + w + "x" + h + " @ 30fps, " + frameCount + " 帧");

        Frame frame = new Frame(w, h, Frame.DEPTH_UBYTE, 3);
        frame.image = new ByteBuffer[3];
        int ySize = w * h;
        int uvSize = (w / 2) * (h / 2);
        ByteBuffer yBuf = ByteBuffer.allocateDirect(ySize);
        ByteBuffer uBuf = ByteBuffer.allocateDirect(uvSize);
        ByteBuffer vBuf = ByteBuffer.allocateDirect(uvSize);
        for (int i = 0; i < ySize; i++) yBuf.put((byte) ((i * 7) & 0xFF));
        for (int i = 0; i < uvSize; i++) { uBuf.put((byte) 128); vBuf.put((byte) 128); }
        yBuf.flip(); uBuf.flip(); vBuf.flip();
        frame.image[0] = yBuf; frame.image[1] = uBuf; frame.image[2] = vBuf;
        frame.imageStride = w; frame.imageWidth = w; frame.imageHeight = h;

        encoder.forceKeyFrame();
        long totalBytes = 0;
        long start = System.nanoTime();
        long firstKeySize = 0;
        int keyFrames = 0;
        int outputFrames = 0;
        int emptyFrames = 0;
        boolean firstKeyHasSps = false, firstKeyHasPps = false;

        for (int i = 0; i < frameCount; i++) {
            boolean isKey = (i % 150 == 0);
            frame.keyFrame = isKey;
            byte[] data = encoder.encode(frame);
            if (data == null || data.length == 0) {
                emptyFrames++;
                continue;
            }
            outputFrames++;
            totalBytes += data.length;
            if (isKey) {
                keyFrames++;
                if (firstKeySize == 0) {
                    firstKeySize = data.length;
                    for (int j = 0; j < data.length - 4; j++) {
                        if (data[j]==0 && data[j+1]==0 && data[j+2]==0 && data[j+3]==1) {
                            int nalType = data[j+4] & 0x1f;
                            if (nalType == 7) firstKeyHasSps = true;
                            if (nalType == 8) firstKeyHasPps = true;
                        }
                    }
                }
            }
        }
        long elapsed = System.nanoTime() - start;
        double seconds = elapsed / 1_000_000_000.0;
        double fps = outputFrames / seconds;
        double avgBytes = outputFrames > 0 ? (double) totalBytes / outputFrames : 0;
        double bitrateKbps = seconds > 0 ? (totalBytes * 8.0 / 1000.0) / seconds : 0;

        System.out.println("--- 编码性能 ---");
        System.out.println("总耗时: " + String.format("%.2f", seconds) + " s");
        System.out.println("输出帧数: " + outputFrames + "/" + frameCount
                + " (空帧=" + emptyFrames + ")");
        System.out.println("实际编码 fps: " + String.format("%.1f", fps));
        System.out.println("平均帧大小: " + String.format("%.0f", avgBytes) + " bytes");
        System.out.println("编码比特率: " + String.format("%.0f", bitrateKbps) + " kbps");
        System.out.println("关键帧数量: " + keyFrames);
        System.out.println("首关键帧大小: " + firstKeySize + " bytes");
        System.out.println("首关键帧含 SPS: " + firstKeyHasSps + ", 含 PPS: " + firstKeyHasPps);

        if (firstKeyHasSps && firstKeyHasPps) {
            System.out.println("[PASS] 关键帧含 SPS+PPS");
        } else {
            System.out.println("[FAIL] 关键帧缺 SPS/PPS");
        }

        encoder.close();
    }
}

