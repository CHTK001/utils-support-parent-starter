package com.chua.image.support.compression;

import com.chua.common.support.image.compression.CompressOptions;
import com.chua.common.support.image.compression.DeflateEngine;
import com.chua.common.support.image.compression.PaletteQuantizer;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Zipic（原 ZipPic）风格的多格式重编码引擎。
 *
 * <p>与 {@link TinyPngImageCompressor} 的路线不同：本引擎做的是<b>整图重编码 + 格式转换</b>。
 * 它面向的是「一堆 HEIC / 相机 RAW / 大尺寸 TIFF，要转成能直接发网页的 JPEG 或 WebP」这类场景，
 * 压缩等级 1~6 统一映射为「质量与量化强度」两档旋钮。
 *
 * <h3>等级到管线的映射</h3>
 * <table border="1">
 *   <caption>压缩等级映射</caption>
 *   <tr><th>等级</th><th>JPEG/WebP 质量</th><th>PNG 调色板色数</th><th>处理方式</th></tr>
 *   <tr><td>1</td><td>92</td><td>256</td><td>仅重编码，不缩放</td></tr>
 *   <tr><td>2</td><td>85</td><td>256</td><td>重编码</td></tr>
 *   <tr><td>3</td><td>78</td><td>256</td><td>重编码</td></tr>
 *   <tr><td>4</td><td>72</td><td>128</td><td>重编码</td></tr>
 *   <tr><td>5</td><td>65</td><td>64</td><td>重编码</td></tr>
 *   <tr><td>6</td><td>60</td><td>32</td><td>重编码，必要时顺带转格式</td></tr>
 * </table>
 *
 * <h3>格式覆盖与降级策略</h3>
 * <p>本引擎声明支持 12 种格式，但<b>能写出哪些格式取决于运行环境注册了哪些 ImageIO 插件</b>
 * （webp 来自 twelvemonkeys，heic 来自本模块的 {@code HEICImageWriterSpi}，
 * avif / jxl 需要应用侧自行引入对应插件）。
 * 因此本引擎采用「尽力而为」策略：
 * <ul>
 *   <li>目标格式没有写出器时，先按 {@link CompressOptions#targetFormat()} 转换；
 *       没有指定目标格式则退回 PNG——任何图片转 PNG 都是安全的</li>
 *   <li>PNG 始终走 {@link #compressPng}，保证压缩收益来自量化而非格式转换带来的有损</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 * @see TinyPngImageCompressor
 */
@Slf4j
@Spi(value = {"zipic", "zippic"}, order = 90)
@SpiDescribe("Zipic 风格：多格式整图重编码，支持压缩等级 1~6")
public class ZipicImageCompressor extends AbstractImageCompressor {

    /**
     * 本引擎声明支持的格式
     */
    private static final Set<String> FORMATS = Set.of(
            "jpeg", "png", "webp", "heic", "avif", "jxl", "tiff", "gif", "bmp", "icns", "apng");

    @Override
    protected byte[] doCompress(BufferedImage image, String format, CompressOptions options) throws Exception {
        String target = options.targetFormat() == null ? format : normalize(options.targetFormat());

        // PNG 是压缩收益最确定的格式：量化 + deflate 优化都由本模块自己掌控
        if ("png".equals(target)) {
            return compressPng(image, options);
        }

        if (isLossy(target)) {
            String writable = writableFormat(target);
            if ("jpeg".equals(writable)) {
                return compressJpeg(image, options);
            }
            if ("png".equals(writable)) {
                return compressPng(image, options);
            }
            return compressLossy(image, writable, options);
        }

        // 无损或半无损格式：交给通用 ImageIO 写出器，失败时退回 PNG
        try {
            return compressLossy(image, target, options);
        } catch (IOException e) {
            log.warn("[图片压缩][{}] 格式 {} 不可写，退回 PNG: {}", name(), target, e.getMessage());
            return compressPng(image, options);
        }
    }

    /**
     * 判断格式是否为有损格式
     *
     * @param format 格式名
     * @return 有损返回 {@code true}
     */
    private boolean isLossy(String format) {
        return "jpeg".equals(format) || "webp".equals(format) || "avif".equals(format) || "jxl".equals(format);
    }

    /**
     * 在候选格式中挑一个当前运行环境确实能写出的有损格式
     *
     * @param preferred 首选格式
     * @return 可写出的格式名；候选全部不可写时返回 {@code png}
     */
    private String writableFormat(String preferred) {
        if (ImageIO.getImageWritersByFormatName(preferred).hasNext()) {
            return preferred;
        }
        for (String candidate : new String[]{"jpeg", "webp"}) {
            if (ImageIO.getImageWritersByFormatName(candidate).hasNext()) {
                return candidate;
            }
        }
        return "png";
    }

    @Override
    public Set<String> supportedFormats() {
        return FORMATS;
    }

    @Override
    public String name() {
        return "zipic";
    }

    /**
     * 默认构造函数，SPI 实例化入口
     */
    public ZipicImageCompressor() {
        super();
    }

    /**
     * 使用外部引擎构造实例，便于测试时注入替身
     *
     * @param engine    deflate 引擎
     * @param quantizer 调色板量化器
     */
    public ZipicImageCompressor(DeflateEngine engine, PaletteQuantizer quantizer) {
        super(engine, quantizer);
    }
}