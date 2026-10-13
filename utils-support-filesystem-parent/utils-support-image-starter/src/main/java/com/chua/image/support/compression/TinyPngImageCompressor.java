package com.chua.image.support.compression;

import com.chua.common.support.image.compression.CompressLevel;
import com.chua.common.support.image.compression.CompressOptions;
import com.chua.common.support.image.gif.GifEncoder;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;

/**
 * TinyPNG 风格压缩引擎。
 *
 * <p>TinyPNG 的核心不是「换一个更强的压缩算法」，而是<b>先有损、后无损</b>的两段式管线：
 * <ol>
 *   <li><b>有损量化</b>：把 24-bit RGB(A) 降到 8-bit 索引色调色板。
 *       这一步单独就贡献了绝大部分体积收益（像素数据降到 1/3~1/4），
 *       并且是<b>有损</b>的——但只要在 Lab 感知色彩空间里合并肉眼难辨的颜色，
 *       视觉上基本无损。官方文档原话是 "smart lossy compression"。</li>
 *   <li><b>无损 deflate 优化</b>：在量化后的索引数据上重跑扫描线过滤器策略选择、
 *       deflate 参数穷举，并剥离 tEXt/iTXt 等对网页渲染无用的元数据块。</li>
 * </ol>
 *
 * <p>本实现对应关系：
 * <table border="1">
 *   <caption>TinyPNG 能力与本实现对照</caption>
 *   <tr><th>TinyPNG 环节</th><th>本实现</th></tr>
 *   <tr><td>调色板量化（感知色彩空间）</td><td>{@link #quantizer}，默认纯 Java 中位切分</td></tr>
 *   <tr><td>有序抖动</td><td>Floyd-Steinberg，由 {@link CompressLevel} 控制开关</td></tr>
 *   <tr><td>deflate 参数穷举（zopfli）</td><td>{@link #deflateEngine}，默认 JDK + 级别×策略矩阵试压</td></tr>
 *   <tr><td>元数据块剥离</td><td>只写 IHDR/PLTE/tRNS/IDAT/IEND</td></tr>
 *   <tr><td>JPEG 重编码</td><td>复用本仓库 libjpeg-turbo 原生编码器（{@code ImageEncoder} SPI）</td></tr>
 * </table>
 *
 * <h3>与 Zipic 的差异</h3>
 * <p>{@link ZipicImageCompressor} 走的是「整图重编码 + 格式转换」路线，
 * 更适合「把 HEIC 转成 JPEG 并缩小体积」这类需求；本引擎则专注「同格式内把体积压到最小」。
 *
 * @author CH
 * @since 4.0.0.43
 * @see ZipicImageCompressor
 */
@Slf4j
@Spi(value = {"tinypng", "tinypng-style"}, order = 100)
@SpiDescribe("TinyPNG 风格：有损量化 + 无损 deflate 优化")
public class TinyPngImageCompressor extends AbstractImageCompressor {

    /**
     * 本引擎支持的格式
     */
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "webp", "gif", "bmp");

    @Override
    protected byte[] doCompress(BufferedImage image, String format, CompressOptions options) throws Exception {
        return switch (format) {
            case "png" -> compressPng(image, options);
            case "jpeg" -> compressJpeg(image, options);
            case "webp" -> compressLossy(image, "webp", options);
            case "gif" -> compressGif(image, options);
            default -> compressLossy(image, "png", options);
        };
    }

    /**
     * GIF 单帧压缩：按目标色数重算调色板，交给 {@link GifEncoder} 用 LZW 重新编码
     *
     * @param image   图像
     * @param options 压缩参数
     * @return GIF 字节
     * @throws Exception 当量化或编码失败时
     */
    protected byte[] compressGif(BufferedImage image, CompressOptions options) throws Exception {
        int colors = options.isQuantized() ? Math.min(options.maxColors(), 256) : 256;
        GifEncoder encoder = new GifEncoder();
        if (options.level().getIndex() <= 1) {
            encoder.setQuality(Math.min(10, Math.max(1, colors / 32)));
        } else {
            encoder.setQuality(Math.max(1, 10 - options.level().getIndex()));
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, image.getWidth() * image.getHeight() / 4))) {
            if (!encoder.start(out)) {
                throw new IOException("GIF 编码器启动失败");
            }
            if (!encoder.addFrame(image)) {
                throw new IOException("GIF 帧写入失败");
            }
            if (!encoder.finish()) {
                throw new IOException("GIF 编码收尾失败");
            }
            return out.toByteArray();
        }
    }

    @Override
    public Set<String> supportedFormats() {
        return FORMATS;
    }

    @Override
    public String name() {
        return "tinypng";
    }
}