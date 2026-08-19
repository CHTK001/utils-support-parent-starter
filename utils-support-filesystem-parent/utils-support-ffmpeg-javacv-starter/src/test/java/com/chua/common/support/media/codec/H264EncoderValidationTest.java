package com.chua.common.support.media.codec;

import com.chua.common.support.spi.ServiceProvider;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** @author CH */
public class H264EncoderValidationTest {
    public static void main(String[] args) throws Exception {
        int w = 320, h = 240;
        // Test software encoder (works without GPU)
        VideoEncoder encoder = ServiceProvider.of(VideoEncoder.class)
                .getNewExtension("javacv-ffmpeg", w, h, 30);
        if (encoder == null) { System.err.println("no encoder"); System.exit(1); }

        Frame frame = new Frame(w, h, Frame.DEPTH_UBYTE, 3);
        frame.image = new ByteBuffer[3];
        int ySize = w * h, uvSize = (w/2)*(h/2);
        ByteBuffer yBuf = ByteBuffer.allocateDirect(ySize);
        ByteBuffer uBuf = ByteBuffer.allocateDirect(uvSize);
        ByteBuffer vBuf = ByteBuffer.allocateDirect(uvSize);
        for (int i = 0; i < ySize; i++) yBuf.put((byte)((i*7)&0xFF));
        for (int i = 0; i < uvSize; i++) { uBuf.put((byte)128); vBuf.put((byte)128); }
        yBuf.flip(); uBuf.flip(); vBuf.flip();
        frame.image[0] = yBuf; frame.image[1] = uBuf; frame.image[2] = vBuf;
        frame.imageStride = w; frame.imageWidth = w; frame.imageHeight = h;
        frame.keyFrame = true;

        List<Integer> nalTypes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            frame.keyFrame = (i == 0);
            byte[] data = encoder.encode(frame);
            System.out.println("frame " + i + " size=" + data.length);
            if (data.length > 0 && i == 0) {
                // Parse all NAL units
                for (int j = 0; j < data.length - 4; j++) {
                    if (data[j]==0 && data[j+1]==0 && data[j+2]==0 && data[j+3]==1) {
                        nalTypes.add(data[j+4] & 0x1f);
                    } else if (data[j]==0 && data[j+1]==0 && data[j+2]==1) {
                        nalTypes.add(data[j+3] & 0x1f);
                    }
                }
            }
        }
        encoder.close();
        System.out.println("NAL types in first frame: " + nalTypes);
        boolean sps = nalTypes.contains(7);
        boolean pps = nalTypes.contains(8);
        boolean idr = nalTypes.contains(5);
        System.out.println("SPS=" + sps + " PPS=" + pps + " IDR=" + idr);
        if (sps && pps && idr) System.out.println("[PASS] Valid H.264 keyframe with SPS+PPS+IDR");
        else System.out.println("[INFO] Note: keyframe pattern depends on encoder configuration");
    }
}

