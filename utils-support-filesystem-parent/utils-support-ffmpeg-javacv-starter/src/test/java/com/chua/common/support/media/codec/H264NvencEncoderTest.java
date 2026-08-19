package com.chua.common.support.media.codec;

import com.chua.common.support.spi.ServiceProvider;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;

/** @author CH */
public class H264NvencEncoderTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== H264NvencEncoder SPI 测试 (nvenc) ===");
        int w = 320, h = 240;
        VideoEncoder encoder = ServiceProvider.of(VideoEncoder.class)
                .getNewExtension("nvenc", w, h, 30);
        if (encoder == null) {
            System.err.println("[INFO] 未找到 nvenc 编码器");
            System.exit(1);
        }

        System.out.println("加载到编码器: " + encoder.getClass().getSimpleName()
                + ", name=" + encoder.getCodecName()
                + ", codecId=" + encoder.getCodecId()
                + ", hw=" + encoder.isHardwareAccelerated());

        // 构造 YUV420P Frame (手动分配 image 数组)
        Frame frame = new Frame(w, h, Frame.DEPTH_UBYTE, 3);
        frame.image = new ByteBuffer[3];
        int ySize = w * h;
        int uvSize = (w / 2) * (h / 2);
        ByteBuffer yBuf = ByteBuffer.allocateDirect(ySize);
        ByteBuffer uBuf = ByteBuffer.allocateDirect(uvSize);
        ByteBuffer vBuf = ByteBuffer.allocateDirect(uvSize);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                yBuf.put((byte) ((x + y) & 0xFF));
            }
        }
        for (int i = 0; i < uvSize; i++) {
            uBuf.put((byte) 128);
            vBuf.put((byte) 128);
        }
        yBuf.flip();
        uBuf.flip();
        vBuf.flip();
        frame.image[0] = yBuf;
        frame.image[1] = uBuf;
        frame.image[2] = vBuf;
        frame.imageStride = w;
        frame.imageWidth = w;
        frame.imageHeight = h;
        frame.keyFrame = true;

        byte[] first = encoder.encode(frame);
        if (first == null || first.length == 0) {
            System.err.println("[FAIL] 编码返回空数据");
            encoder.close();
            System.exit(1);
        }
        System.out.println("首帧大小: " + first.length + " bytes");

        boolean hasSPS = false, hasPPS = false;
        for (int i = 0; i < first.length - 4; i++) {
            if (first[i] == 0 && first[i + 1] == 0 && first[i + 2] == 0 && first[i + 3] == 1) {
                int nalType = first[i + 4] & 0x1f;
                if (nalType == 7) hasSPS = true;
                if (nalType == 8) hasPPS = true;
            }
        }
        System.out.println("首帧含 SPS: " + hasSPS);
        System.out.println("首帧含 PPS: " + hasPPS);
        if (hasSPS && hasPPS) {
            System.out.println("[PASS] 关键帧已包含 SPS 与 PPS");
        } else {
            System.err.println("[FAIL] 关键帧缺失 SPS/PPS");
        }

        for (int i = 0; i < 3; i++) {
            frame.keyFrame = false;
            byte[] data = encoder.encode(frame);
            System.out.println("非关键帧 " + i + ": " + (data == null ? -1 : data.length) + " bytes");
        }

        encoder.close();
        System.out.println("测试完成");
    }
}

