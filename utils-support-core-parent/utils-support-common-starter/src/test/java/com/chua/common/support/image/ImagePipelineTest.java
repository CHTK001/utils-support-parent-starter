package com.chua.common.support.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImagePipeline 单元测试。
 *
 * <p>验证默认全关闭、灰度化、二值化、降噪、腐蚀、膨胀等图像处理管线步骤。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class ImagePipelineTest {

    /**
     * 生成 64x64 测试图（左侧黑、右侧白、中部灰），便于验证二值化等操作。
     *
     * @return PNG 字节
     */
    private byte[] testImageBytes() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if (x < 21) {
                    img.setRGB(x, y, 0x000000);
                } else if (x < 43) {
                    img.setRGB(x, y, 0x808080);
                } else {
                    img.setRGB(x, y, 0xFFFFFF);
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * 解码图像字节。
     *
     * @param bytes PNG 字节
     * @return BufferedImage
     */
    private BufferedImage decode(byte[] bytes) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(img, "解码失败");
        return img;
    }

    /**
     * 默认全关闭：处理后的图应保持尺寸与原图一致。
     */
    @Test
    void allOffReturnsOriginalSize() throws Exception {
        byte[] in = testImageBytes();
        byte[] out = ImagePipeline.builder().build().process(in);
        assertEquals(decode(in).getWidth(), decode(out).getWidth(), "宽度应一致");
        assertEquals(decode(in).getHeight(), decode(out).getHeight(), "高度应一致");
    }

    /**
     * 灰度化：RGB 三分量相等（灰色）。
     */
    @Test
    void grayscaleMakesGrayPixels() throws Exception {
        byte[] out = ImagePipeline.builder().grayscale(true).build().process(testImageBytes());
        BufferedImage img = decode(out);
        for (int y = 0; y < img.getHeight(); y += 8) {
            for (int x = 0; x < img.getWidth(); x += 8) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                assertTrue(Math.abs(r - g) <= 2 && Math.abs(g - b) <= 2,
                        "灰度像素 RGB 应相近: (" + r + "," + g + "," + b + ")");
            }
        }
    }

    /**
     * 二值化：所有像素应仅为黑或白。
     */
    @Test
    void binarizeProducesOnlyBlackWhite() throws Exception {
        byte[] out = ImagePipeline.builder().binarize(true, 128).build().process(testImageBytes());
        BufferedImage img = decode(out);
        for (int y = 0; y < img.getHeight(); y += 4) {
            for (int x = 0; x < img.getWidth(); x += 4) {
                int val = img.getRGB(x, y) & 0xFF;
                assertTrue(val == 0 || val == 255, "二值化像素应为 0 或 255，实际 " + val);
            }
        }
    }

    /**
     * 降噪 / 腐蚀 / 膨胀：各管线处理应正常输出且保尺寸。
     */
    @Test
    void morphologicalOpsKeepSize() throws Exception {
        byte[] in = testImageBytes();
        int w = decode(in).getWidth();
        int h = decode(in).getHeight();

        byte[] denoised = ImagePipeline.builder().denoise(true, 1).build().process(in);
        assertEquals(w, decode(denoised).getWidth());
        assertEquals(h, decode(denoised).getHeight());

        byte[] eroded = ImagePipeline.builder().erode(true, 3).build().process(in);
        assertEquals(w, decode(eroded).getWidth());
        assertEquals(h, decode(eroded).getHeight());

        byte[] dilated = ImagePipeline.builder().dilate(true, 3).build().process(in);
        assertEquals(w, decode(dilated).getWidth());
        assertEquals(h, decode(dilated).getHeight());

        byte[] all = ImagePipeline.builder()
                .grayscale(true)
                .binarize(true, 128)
                .denoise(true, 1)
                .erode(true, 3)
                .dilate(true, 3)
                .build()
                .process(in);
        assertEquals(w, decode(all).getWidth());
        assertEquals(h, decode(all).getHeight());
    }
}