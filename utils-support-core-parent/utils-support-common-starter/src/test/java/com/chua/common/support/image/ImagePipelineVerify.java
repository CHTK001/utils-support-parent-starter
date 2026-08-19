package com.chua.common.support.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * ImagePipeline 手动验证（main 方式）。
 *
 * <p>验证默认关闭、灰度化、二值化、降噪、腐蚀、膨胀及全链路处理，
 * 并统计处理前后像素特征。</p>
 *
 * @since 4.0.0.42
 */
public final class ImagePipelineVerify {

    private ImagePipelineVerify() {
    }

    /**
     * 入口。
     *
     * @param args 忽略
     */
    public static void main(String[] args) throws Exception {
        byte[] in = testImageBytes();
        System.out.println("[ImagePipeline] 测试图 64x64: 左黑/中灰/右白");

        // 1) 默认全关闭
        byte[] out0 = ImagePipeline.builder().build().process(in);
        System.out.println("[all-off] 尺寸保持: " + size(out0) + " (原 " + size(in) + ")");

        // 2) 灰度化
        byte[] out1 = ImagePipeline.builder().grayscale(true).build().process(in);
        System.out.println("[grayscale] 灰度化完成: " + size(out1) + ", 中部像素 RGB 应相等");

        // 3) 二值化
        byte[] out2 = ImagePipeline.builder().binarize(true, 128).build().process(in);
        System.out.println("[binarize] 二值化完成: " + size(out2) + ", 应为黑白两值");

        // 4) 降噪
        byte[] out3 = ImagePipeline.builder().denoise(true, 1).build().process(in);
        System.out.println("[denoise] 降噪完成: " + size(out3));

        // 5) 腐蚀
        byte[] out4 = ImagePipeline.builder().erode(true, 3).build().process(in);
        System.out.println("[erode] 腐蚀完成: " + size(out4));

        // 6) 膨胀
        byte[] out5 = ImagePipeline.builder().dilate(true, 3).build().process(in);
        System.out.println("[dilate] 膨胀完成: " + size(out5));

        // 7) 全链路
        byte[] out6 = ImagePipeline.builder()
                .grayscale(true).binarize(true, 128)
                .denoise(true, 1).erode(true, 3).dilate(true, 3)
                .build().process(in);
        System.out.println("[full] 全链路完成: " + size(out6));
        System.out.println("\n全部步骤执行成功 ✅");
    }

    /**
     * 生成 64x64 测试图。
     *
     * @return PNG 字节
     */
    private static byte[] testImageBytes() throws Exception {
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
     * 输出图像尺寸。
     *
     * @param bytes 图像字节
     * @return "WxH"
     */
    private static String size(byte[] bytes) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        return img.getWidth() + "x" + img.getHeight();
    }
}