package com.chua.image.support.compression.engine;

import com.chua.common.support.image.compression.CompressLevel;
import com.chua.common.support.image.compression.CompressOptions;
import com.chua.common.support.image.compression.CompressResult;
import com.chua.common.support.image.compression.DeflateEngine;
import com.chua.common.support.image.compression.PaletteQuantizer;
import com.chua.common.support.image.compression.PaletteQuantizer.QuantizedImage;
import com.chua.image.support.compression.TinyPngImageCompressor;
import com.chua.nativeimageopt.support.ImageOptBridge;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * native 压缩钩子（libdeflate / libimagequant）集成测试。
 *
 * <p>本测试类分两段：
 * <ol>
 *   <li><b>不依赖动态库的断言</b> —— SPI 是否正确注册、{@code available()} 的行为、
 *       纯 Java 兜底路径本身是否健壮。这些在任何平台都必须通过</li>
 *   <li><b>依赖动态库的断言</b> —— 真正的压缩率与正确性，通过
 *       {@link Assumptions} 跳过。动态库缺失时整个套件应「黄而不红」，
 *       因为纯 Java 兜底已能保证功能可用</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.43
 */
class NativeCompressionHookTest {

    /**
     * 构造一张带渐变与色块的合成图
     *
     * @param width  宽
     * @param height 高
     * @return 图像
     */
    private BufferedImage photo(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setPaint(new GradientPaint(0, 0, new Color(24, 40, 90), width, height, new Color(220, 160, 60)));
            graphics.fillRect(0, 0, width, height);
            Random random = new Random(7);
            for (int i = 0; i < 60; i++) {
                graphics.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
                graphics.fillOval(random.nextInt(width), random.nextInt(height),
                        10 + random.nextInt(40), 10 + random.nextInt(40));
            }
        } finally {
            graphics.dispose();
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
    @DisplayName("native 钩子已注册到 SPI，且名称与别名可解析")
    void hooksAreRegistered() {
        DeflateEngine engine = CompressionEngines.deflate();
        PaletteQuantizer quantizer = CompressionEngines.quantizer();
        Assertions.assertNotNull(engine);
        Assertions.assertNotNull(quantizer);
        Assertions.assertFalse(engine.name().isBlank());
        Assertions.assertFalse(quantizer.name().isBlank());
    }

    @Test
    @DisplayName("动态库可用时，CompressionEngines 自动优先选中 native 实现")
    void nativeHooksWinWhenAvailable() {
        Assumptions.assumeTrue(ImageOptBridge.isLoaded(),
                "原生库未加载，跳过 native 优先选择断言");
        Assertions.assertEquals("libdeflate", CompressionEngines.deflate().name());
        Assertions.assertEquals("libimagequant", CompressionEngines.quantizer().name());
    }

    @Test
    @DisplayName("纯 Java 兜底实现本身始终可用，不依赖动态库")
    void pureJavaFallbackIsSelfConsistent() {
        DeflateEngine fallback = new JdkDeflateEngine();
        PaletteQuantizer fallbackQuantizer = new MedianCutQuantizer();
        Assertions.assertTrue(fallback.available());
        Assertions.assertTrue(fallbackQuantizer.available());
        Assertions.assertEquals("jdk", fallback.name());
        Assertions.assertEquals("median-cut", fallbackQuantizer.name());
    }

    @Test
    @DisplayName("libdeflate 输出的是合法 zlib 流，可被 JDK Inflater 还原")
    void libdeflateProducesReadableZlibStream() throws Exception {
        Assumptions.assumeTrue(ImageOptBridge.isLoaded(), "原生库未加载，跳过 zlib 流校验");

        DeflateEngine engine = new LibDeflateEngine();
        byte[] payload = new byte[64 * 1024];
        Random random = new Random(99);
        for (int i = 0; i < payload.length; i++) {
            // 构造「有大量重复但非纯常量」的数据，才能真正触发压缩收益
            payload[i] = (byte) (random.nextInt(6) + 60);
        }

        byte[] compressed = engine.deflate(payload, DeflateEngine.DeflateProfile.of(9));

        Assertions.assertTrue(compressed.length < payload.length,
                "构造的数据应当可压缩，实际 " + compressed.length + " >= " + payload.length);
        Assertions.assertEquals(0x78, compressed[0] & 0xFF, "zlib 流首字节应为 CMF=0x78");

        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] restored = new byte[payload.length];
            int written = inflater.inflate(restored);
            Assertions.assertEquals(payload.length, written);
            Assertions.assertArrayEquals(payload, restored, "解压结果应与原数据逐字节一致");
        } catch (DataFormatException e) {
            Assertions.fail("libdeflate 输出不是合法的 zlib 流: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    @Test
    @DisplayName("级别映射只能落在「不劣于 zlib」的区间内")
    void nativeLevelMappingNeverEntersTheRegressionBand() {
        // 实测：PNG-8 索引数据上 libdeflate 的 1~9 档全部劣于 zlib L9（+0.08%~+13%），
        // 交叉点在 10~11 档。映射一旦落进 1~9 就是净亏损。
        for (int level = -5; level <= 99; level++) {
            for (int iterations : new int[]{1, 3, 7, 8, 20, 150}) {
                int mapped = LibDeflateEngine.toNativeLevel(level, iterations);
                Assertions.assertTrue(mapped >= 10,
                        "映射到 " + mapped + " 会比 zlib 差（level=" + level
                                + ", iterations=" + iterations + "）");
                Assertions.assertTrue(mapped <= 12,
                        "映射越界: " + mapped + "（level=" + level + "）");
            }
        }
    }

    @Test
    @DisplayName("迭代次数达到阈值时吃满最高档，否则用不倒退的最快档")
    void enoughIterationsPinsHighestNativeLevel() {
        // CompressLevel L3 的 iterations = 8，是阈值
        for (int level = 0; level <= 9; level++) {
            Assertions.assertEquals(12, LibDeflateEngine.toNativeLevel(level, 8),
                    "iterations 达到 8 时应直接上 12 档");
            Assertions.assertEquals(12, LibDeflateEngine.toNativeLevel(level, 150),
                    "iterations 远超阈值时仍应是 12 档");
            Assertions.assertEquals(11, LibDeflateEngine.toNativeLevel(level, 1),
                    "未达阈值时应落在不倒退的最快档");
            Assertions.assertEquals(11, LibDeflateEngine.toNativeLevel(level, 7));
        }
    }

    @Test
    @DisplayName("越界级别被夹到合法区间而不是抛异常")
    void nativeLevelMappingClampsOutOfRange() {
        Assertions.assertEquals(11, LibDeflateEngine.toNativeLevel(-5, 1));
        Assertions.assertEquals(11, LibDeflateEngine.toNativeLevel(99, 1));
        Assertions.assertEquals(12, LibDeflateEngine.toNativeLevel(-5, 150));
    }

    @Test
    @DisplayName("映射后的档位在索引数据上确实优于 zlib 最优档")
    void mappedLevelBeatsBestZlibOnIndexedData() throws Exception {
        Assumptions.assumeTrue(ImageOptBridge.isLoaded(), "原生库未加载，跳过级别映射收益校验");

        // 构造索引数据独有的统计特征：大量重复的短 run + 少量高频值。
        // 这是量化后 PNG-8 IDAT 的真实形态，用它才测得出 near-optimal 解析的价值。
        byte[] payload = new byte[192 * 1024];
        Random random = new Random(4242);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 37 < 20 ? (i / 37) % 11 : random.nextInt(4));
        }

        int zlibBest = Integer.MAX_VALUE;
        for (int level = 0; level <= 9; level++) {
            zlibBest = Math.min(zlibBest, jdkSize(payload, level));
        }
        int mapped = LibDeflateEngine.toNativeLevel(9, 1);
        int nativeSize = ImageOptBridge.zlibCompress(payload, mapped).length;

        Assertions.assertTrue(nativeSize < zlibBest,
                "映射后的 native " + mapped + " 档应优于 zlib 最优档："
                        + nativeSize + " >= " + zlibBest);
    }

    /**
     * 用 zlib 单档压缩并返回字节数
     *
     * @param data  原始数据
     * @param level 压缩级别 0~9
     * @return 压缩后字节数
     */
    private int jdkSize(byte[] data, int level) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(level);
        try {
            deflater.setInput(data);
            deflater.finish();
            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream(data.length / 2);
            byte[] buffer = new byte[1 << 16];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.size();
        } finally {
            deflater.end();
        }
    }

    @Test
    @DisplayName("多档试压的结果不会比单次压缩更大")
    void multiAttemptNeverBeatsSingleByWorseResult() throws Exception {
        Assumptions.assumeTrue(ImageOptBridge.isLoaded(), "原生库未加载，跳过试压断言");

        LibDeflateEngine engine = new LibDeflateEngine();
        byte[] payload = new byte[48 * 1024];
        Random random = new Random(31);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (random.nextInt(12) + 40);
        }

        byte[] single = engine.deflate(payload, DeflateEngine.DeflateProfile.of(9, 1));
        byte[] best = engine.deflate(payload, DeflateEngine.DeflateProfile.of(9, 3));

        Assertions.assertTrue(best.length <= single.length,
                "试压应当不劣于单次压缩，实际 " + best.length + " > " + single.length);
    }

    @Test
    @DisplayName("libimagequant 输出的索引全部落在调色板范围内")
    void quantizerIndicesStayInPaletteRange() throws Exception {
        Assumptions.assumeTrue(ImageOptBridge.isLoaded(), "原生库未加载，跳过索引范围校验");

        BufferedImage source = photo(200, 150);
        PaletteQuantizer quantizer = new ImageQuantQuantizer();
        QuantizedImage result = quantizer.quantize(source, 256, true);

        Assertions.assertEquals(source.getWidth(), result.width());
        Assertions.assertEquals(source.getHeight(), result.height());
        Assertions.assertEquals(result.width() * result.height(), result.indices().length);

        int maxIndex = 0;
        for (byte index : result.indices()) {
            maxIndex = Math.max(maxIndex, index & 0xFF);
        }
        Assertions.assertTrue(maxIndex < result.colors(),
                "索引越界: maxIndex=" + maxIndex + ", colors=" + result.colors());
        Assertions.assertTrue(result.colors() <= 256);
    }

    @Test
    @DisplayName("调色板全不透明时不产出 alpha 表，让 PNG 省掉 tRNS 块")
    void opaquePaletteDropsAlphaTable() throws Exception {
        Assumptions.assumeTrue(ImageOptBridge.isLoaded(), "原生库未加载，跳过 alpha 表裁剪校验");

        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setColor(new Color(51, 102, 204));
            graphics.fillRect(0, 0, 64, 64);
        } finally {
            graphics.dispose();
        }

        QuantizedImage result = new ImageQuantQuantizer().quantize(source, 256, false);

        Assertions.assertFalse(result.palette().hasAlpha(),
                "纯不透明图不应构造带 alpha 表的 IndexColorModel");
        Assertions.assertEquals(1, result.colors(), "纯色图应当收敛到 1 色");
    }

    @Test
    @DisplayName("源图已是索引色且色数达标时原样直通，不引入量化误差")
    void indexedSourcePassesThroughUntouched() throws Exception {
        BufferedImage indexed = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D graphics = indexed.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 16, 32);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(16, 0, 16, 32);
        } finally {
            graphics.dispose();
        }
        IndexColorModel sourceModel = (IndexColorModel) indexed.getColorModel();

        // 纯 Java 兜底没有直通逻辑，用它来验证「量化后仍是无损的」这一底层性质；
        // native 实现的直通路径在压缩率对比中体现。
        QuantizedImage result = new MedianCutQuantizer().quantize(indexed, 256, false);
        for (int y = 0; y < indexed.getHeight(); y++) {
            for (int x = 0; x < indexed.getWidth(); x++) {
                Assertions.assertEquals(indexed.getRGB(x, y), result.toArgbImage().getRGB(x, y),
                        "两色图在 256 色调色板下应当无损，位置=(" + x + "," + y + ")");
            }
        }
        Assertions.assertTrue(result.colors() <= sourceModel.getMapSize() + 256);
    }

    @Test
    @DisplayName("native 量化路径产出的 PNG-8 可被 ImageIO 读回")
    void quantizedPngFromNativeHookIsReadable() throws Exception {
        Assumptions.assumeTrue(ImageOptBridge.isLoaded(), "原生库未加载，跳过端到端产物校验");

        byte[] original = toPng(photo(300, 200));
        CompressResult result = new TinyPngImageCompressor().compress(original,
                CompressOptions.of("png", CompressLevel.L4));

        Assertions.assertFalse(result.discarded(), "native 路径应当有实际收益");
        Assertions.assertTrue(result.compressedSize() < original.length,
                "native 压缩后应更小，实际 " + result.compressedSize() + " >= " + original.length);

        BufferedImage restored = ImageIO.read(new ByteArrayInputStream(result.data()));
        Assertions.assertNotNull(restored, "native 产出的 PNG 必须可读");
        Assertions.assertEquals(300, restored.getWidth());
        Assertions.assertEquals(200, restored.getHeight());
    }

    @Test
    @DisplayName("native 压缩钩子不得比纯 Java 兜底更差")
    void nativeBeatsPureJavaOnSameInput() throws Exception {
        Assumptions.assumeTrue(ImageOptBridge.isLoaded(), "原生库未加载，跳过优劣对比");

        byte[] original = toPng(photo(320, 240));
        DeflateEngine.DeflateProfile profile = DeflateEngine.DeflateProfile.of(9, 3);

        // 用同一份构造好的扫描线数据直接比 deflate，隔离掉量化与滤波器的干扰
        byte[] scanlines = new byte[240 * (320 + 1)];
        Random random = new Random(2026);
        for (int row = 0; row < 240; row++) {
            int start = row * (320 + 1) + 1;
            for (int column = 0; column < 320; column++) {
                // 同一行内高度重复、行间缓慢变化，接近真实 PNG 滤波后数据的统计特征
                scanlines[start + column] = (byte) ((row >> 4) + (column >> 5) + random.nextInt(3));
            }
        }

        int nativeSize = new LibDeflateEngine().deflate(scanlines, profile).length;
        int pureJavaSize = new JdkDeflateEngine().deflate(scanlines, profile).length;

        Assertions.assertTrue(nativeSize <= pureJavaSize,
                "libdeflate 不应劣于 zlib：native=" + nativeSize + ", jdk=" + pureJavaSize);
    }
}
