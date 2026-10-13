package com.chua.image.support.compression;

import com.chua.common.support.image.compression.CompressLevel;
import com.chua.common.support.image.compression.CompressOptions;
import com.chua.common.support.image.compression.CompressResult;
import com.chua.common.support.image.compression.ImageCompressor;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

/**
 * 图片压缩 SPI 端到端测试。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li><b>产出的文件必须仍然合法可读</b> —— PNG 的 chunk 组装、CRC、滤波器位深换算都是手写的，
 *       一个字节错就会让 ImageIO 读不回来，这是本模块最需要守住的底线</li>
 *   <li><b>确实压缩了</b> —— 在可控的合成图上断言体积确实下降，避免「管线跑通了但没收益」</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.43
 */
class ImageCompressorTest {

    /**
     * 通过 SPI 取得 TinyPNG 风格引擎
     *
     * @return 压缩引擎实例
     */
    private ImageCompressor tinypng() {
        ImageCompressor compressor = ServiceProvider.of(ImageCompressor.class).getNewExtension("tinypng");
        Assertions.assertNotNull(compressor, "未能通过 SPI 找到 tinypng 压缩引擎");
        return compressor;
    }

    /**
     * 通过 SPI 取得 Zipic 风格引擎
     *
     * @return 压缩引擎实例
     */
    private ImageCompressor zipic() {
        ImageCompressor compressor = ServiceProvider.of(ImageCompressor.class).getNewExtension("zipic");
        Assertions.assertNotNull(compressor, "未能通过 SPI 找到 zipic 压缩引擎");
        return compressor;
    }

    /**
     * 构造一张大面积纯色、少量高饱和点缀的合成图。
     *
     * <p>这类图在 24-bit 下数据冗余极大，是量化压缩收益最明显的场景。
     *
     * @param width  宽
     * @param height 高
     * @return 测试用图像
     */
    private BufferedImage syntheticImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setPaint(new GradientPaint(0, 0, new Color(250, 250, 250),
                    width, height, new Color(230, 230, 240)));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(220, 40, 40));
            graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
            graphics.setColor(new Color(40, 90, 200));
            graphics.fillRect(0, height - 20, width, 20);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * 构造一张带透明通道的合成图
     *
     * @param width  宽
     * @param height 高
     * @return 测试用图像
     */
    private BufferedImage alphaImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(20260912L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = (x + y) % 128 < 64 ? 0 : 255;
                image.setRGB(x, y, (alpha << 24) | (random.nextInt(0xFFFFFF)));
            }
        }
        return image;
    }

    /**
     * 把图像编码为 PNG 字节
     *
     * @param image 图像
     * @return PNG 字节
     * @throws Exception 当编码失败时
     */
    private byte[] toPng(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("L0 无损档产出的 PNG 必须可读且像素完全一致")
    void losslessPngKeepsEveryPixel() throws Exception {
        BufferedImage source = syntheticImage(240, 180);
        byte[] original = toPng(source);

        CompressResult result = tinypng().compress(original, CompressOptions.of("png", CompressLevel.L0));

        BufferedImage restored = ImageIO.read(new ByteArrayInputStream(result.data()));
        Assertions.assertNotNull(restored, "L0 产出的 PNG 无法被 ImageIO 读取");
        Assertions.assertEquals(source.getWidth(), restored.getWidth());
        Assertions.assertEquals(source.getHeight(), restored.getHeight());

        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                Assertions.assertEquals(source.getRGB(x, y), restored.getRGB(x, y),
                        "L0 是无损档，像素不应发生变化，位置=(" + x + "," + y + ")");
            }
        }
    }

    @Test
    @DisplayName("L0 无损档确实能剥掉元数据并减小体积")
    void losslessPngShrinks() throws Exception {
        BufferedImage source = syntheticImage(400, 300);
        byte[] original = toPng(source);

        CompressResult result = tinypng().compress(original, CompressOptions.of("png", CompressLevel.L0));

        Assertions.assertFalse(result.discarded(), "L0 应当产生实际压缩收益");
        Assertions.assertTrue(result.compressedSize() < original.length,
                "期望压缩后更小，实际 " + result.compressedSize() + " >= " + original.length);
    }

    @Test
    @DisplayName("L2 量化档产出的 PNG-8 必须可读且体积显著下降")
    void quantizedPngShrinksAndStaysReadable() throws Exception {
        BufferedImage source = syntheticImage(400, 300);
        byte[] original = toPng(source);

        CompressResult result = tinypng().compress(original, CompressOptions.of("png", CompressLevel.L2));

        Assertions.assertFalse(result.discarded());
        BufferedImage restored = ImageIO.read(new ByteArrayInputStream(result.data()));
        Assertions.assertNotNull(restored, "量化档产出的 PNG 无法被 ImageIO 读取");
        Assertions.assertEquals(source.getWidth(), restored.getWidth());
        Assertions.assertEquals(source.getHeight(), restored.getHeight());
        Assertions.assertTrue(result.compressedSize() < original.length,
                "量化后应更小，实际 " + result.compressedSize() + " >= " + original.length);
    }

    @Test
    @DisplayName("带 Alpha 的图在量化后仍保留透明像素")
    void quantizedPngKeepsAlpha() throws Exception {
        BufferedImage source = alphaImage(160, 160);
        byte[] original = toPng(source);

        CompressResult result = tinypng().compress(original, CompressOptions.of("png", CompressLevel.L3));

        BufferedImage restored = ImageIO.read(new ByteArrayInputStream(result.data()));
        Assertions.assertNotNull(restored, "带 Alpha 的量化结果必须可读");
        Assertions.assertTrue(restored.getColorModel().hasAlpha(), "量化结果必须保留 Alpha 通道");

        int transparent = 0;
        for (int y = 0; y < restored.getHeight(); y++) {
            for (int x = 0; x < restored.getWidth(); x++) {
                if ((restored.getRGB(x, y) >>> 24) == 0) {
                    transparent++;
                }
            }
        }
        Assertions.assertTrue(transparent > 0, "量化结果里应仍然存在完全透明的像素");
    }

    @Test
    @DisplayName("JPEG 压缩后体积下降且可正常解码")
    void jpegShrinksAndStaysReadable() throws Exception {
        BufferedImage source = syntheticImage(600, 400);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", out);
        byte[] original = out.toByteArray();

        CompressResult result = tinypng().compress(original,
                CompressOptions.of("jpeg", CompressLevel.L5));

        Assertions.assertTrue(result.compressedSize() < original.length,
                "JPEG 压缩后应更小，实际 " + result.compressedSize() + " >= " + original.length);
        BufferedImage restored = ImageIO.read(new ByteArrayInputStream(result.data()));
        Assertions.assertNotNull(restored, "压缩后的 JPEG 必须可读");
        Assertions.assertEquals(source.getWidth(), restored.getWidth());
        Assertions.assertEquals(source.getHeight(), restored.getHeight());
    }

    @Test
    @DisplayName("Zipic 引擎可把 PNG 转码为 JPEG")
    void zipicConvertsPngToJpeg() throws Exception {
        byte[] original = toPng(syntheticImage(400, 300));

        CompressResult result = zipic().compress(original, CompressOptions.of("png")
                .level(CompressLevel.L4)
                .targetFormat("jpeg"));

        Assertions.assertEquals("jpeg", result.format());
        BufferedImage restored = ImageIO.read(new ByteArrayInputStream(result.data()));
        Assertions.assertNotNull(restored, "转码后的 JPEG 必须可读");
        Assertions.assertEquals(400, restored.getWidth());
        Assertions.assertEquals(300, restored.getHeight());
    }

    @Test
    @DisplayName("两个引擎都能声明自己支持的格式")
    void supportedFormatsAreDeclared() {
        Assertions.assertTrue(tinypng().supports("png"));
        Assertions.assertTrue(tinypng().supports("PNG"));
        Assertions.assertFalse(tinypng().supports("avif"));
        Assertions.assertTrue(zipic().supportedFormats().contains("jpeg"));
        Assertions.assertTrue(zipic().supports("heic"));
    }

    @Test
    @DisplayName("空输入应当直接失败而不是静默返回")
    void emptyInputFails() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> tinypng().compress(new byte[0], CompressOptions.of("png")));
    }

    @Test
    @DisplayName("无法识别的字节应当抛出异常")
    void garbageInputFails() {
        Assertions.assertThrows(Exception.class,
                () -> tinypng().compress(new byte[]{1, 2, 3, 4, 5}, CompressOptions.of("png")));
    }

    @Test
    @DisplayName("压缩结果应当给出可读的描述与统计")
    void resultCarriesStatistics() throws Exception {
        byte[] original = toPng(syntheticImage(320, 240));
        CompressResult result = tinypng().compress(original, CompressOptions.of("png", CompressLevel.L3));

        Assertions.assertTrue(result.describe().contains("tinypng"));
        Assertions.assertTrue(result.savedSize() > 0);
        Assertions.assertTrue(result.savedRatio() > 0);
        Assertions.assertEquals(320, result.sourceSize());
        Assertions.assertEquals(240, result.sourceHeight());
        Assertions.assertEquals(9, result.toMap().size());
    }
}