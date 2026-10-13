package com.chua.image.support.compression;

import com.chua.common.support.codec.image.ImageEncoder;
import com.chua.common.support.image.compression.CompressOptions;
import com.chua.common.support.image.compression.CompressResult;
import com.chua.common.support.image.compression.DeflateEngine;
import com.chua.common.support.image.compression.ImageCompressor;
import com.chua.common.support.image.compression.PaletteQuantizer;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.image.support.compression.engine.CompressionEngines;
import com.chua.image.support.compression.png.PngFilterOptimizer;
import com.chua.image.support.compression.png.PngOptimizer;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Locale;

/**
 * 图片压缩器抽象基类。
 *
 * <p>把两套管线共用的部分收敛到基类，子类只需关心「哪种格式走哪条压缩路径」：
 * <ul>
 *   <li>格式探测 —— 用 ImageIO 的 {@link ImageReader} 反查真实格式，
 *       不信任调用方给的格式串（扩展名与实际内容经常对不上）</li>
 *   <li>解码 → 缩放 → 编码的骨架流程</li>
 *   <li>PNG 无损 / 有损量化的公共实现</li>
 *   <li>「压缩后反而更大则丢弃」的兜底判断</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public abstract class AbstractImageCompressor implements ImageCompressor {

    /**
     * deflate 引擎实例，由 {@link CompressionEngines} 解析，可在构造期替换
     */
    protected final DeflateEngine deflateEngine;

    /**
     * 调色板量化器实例，由 {@link CompressionEngines} 解析，可在构造期替换
     */
    protected final PaletteQuantizer quantizer;

    /**
     * 默认构造函数
     */
    protected AbstractImageCompressor() {
        this(CompressionEngines.deflate(), CompressionEngines.quantizer());
    }

    /**
     * 指定引擎的构造函数
     *
     * @param engine   deflate 引擎
     * @param quantizer 调色板量化器
     */
    protected AbstractImageCompressor(DeflateEngine engine, PaletteQuantizer quantizer) {
        this.deflateEngine = engine;
        this.quantizer = quantizer;
    }

    @Override
    public CompressResult compress(InputStream input, OutputStream output, CompressOptions options) throws Exception {
        byte[] source = readFully(input);
        CompressResult result = compress(source, options);
        output.write(result.data());
        output.flush();
        return result;
    }

    @Override
    public CompressResult compress(byte[] source, CompressOptions options) throws Exception {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("待压缩的图片字节不能为空");
        }
        CompressOptions actual = options == null ? CompressOptions.of(null) : options;
        long start = System.currentTimeMillis();

        String format = detectFormat(source);
        BufferedImage image = decode(source, format);
        int width = image.getWidth();
        int height = image.getHeight();

        BufferedImage scaled = resize(image, actual);
        byte[] compressed = doCompress(scaled, format, actual);
        long cost = System.currentTimeMillis() - start;

        String outputFormat = actual.targetFormat() == null ? format : actual.targetFormat().toLowerCase(Locale.ROOT);
        if (compressed.length >= source.length) {
            return actual.discardWhenLarger()
                    ? CompressResult.discarded(source, format, name(), cost, width, height)
                    : CompressResult.of(compressed, source.length, outputFormat, name(), cost, width, height);
        }
        return CompressResult.of(compressed, source.length, outputFormat, name(), cost, width, height);
    }

    /**
     * 按输入格式执行实际压缩
     *
     * @param image   已解码（并按需缩放）的图像
     * @param format  探测到的输入格式，小写
     * @param options 压缩参数
     * @return 压缩后的字节
     * @throws Exception 当该格式的压缩管线执行失败时
     */
    protected abstract byte[] doCompress(BufferedImage image, String format, CompressOptions options) throws Exception;

    @Override
    public boolean supports(String format) {
        if (format == null) {
            return false;
        }
        return supportedFormats().contains(normalize(format));
    }

    /**
     * 探测图片真实格式
     *
     * @param source 图片字节
     * @return 小写格式名，无法识别时返回 {@code null}
     */
    protected String detectFormat(byte[] source) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (stream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (readers.hasNext()) {
                return normalize(readers.next().getFormatName());
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * 解码图片
     *
     * @param source 图片字节
     * @param format 探测到的格式，仅用于日志
     * @return 解码后的图像
     * @throws IOException 当无法解码时
     */
    protected BufferedImage decode(byte[] source, String format) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) {
            throw new IOException("无法解码图片，探测到的格式=" + format);
        }
        return image;
    }

    /**
     * 按参数执行缩放
     *
     * @param image   原图
     * @param options 压缩参数
     * @return 缩放后的图像；不需要缩放时原样返回
     */
    protected BufferedImage resize(BufferedImage image, CompressOptions options) {
        int targetWidth = options.width();
        int targetHeight = options.height();
        if (targetWidth <= 0 && targetHeight <= 0) {
            return image;
        }
        int width = targetWidth > 0 ? targetWidth : (int) Math.round(image.getWidth() * (targetHeight / (double) image.getHeight()));
        int height = targetHeight > 0 ? targetHeight : (int) Math.round(image.getHeight() * (targetWidth / (double) image.getWidth()));
        BufferedImage scaled = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /**
     * PNG 压缩：需要量化时走有损调色板，否则走无损重编码
     *
     * @param image   图像
     * @param options 压缩参数
     * @return PNG 字节
     * @throws Exception 当 deflate 或量化失败时
     */
    protected byte[] compressPng(BufferedImage image, CompressOptions options) throws Exception {
        DeflateEngine.DeflateProfile profile = DeflateEngine.DeflateProfile.of(
                DeflateEngine.DeflateProfile.toDeflateLevel(options.level()), options.iterations());
        PngFilterOptimizer.Strategy strategy = options.level().getIndex() >= 3
                ? PngFilterOptimizer.Strategy.ENTROPY
                : PngFilterOptimizer.Strategy.MIN_SUM;

        if (!options.isQuantized()) {
            return PngOptimizer.writeTrueColor(image, deflateEngine, profile, strategy);
        }

        PaletteQuantizer.QuantizedImage quantized = quantizer.quantize(image, options.maxColors(), options.dithering());
        return PngOptimizer.writeIndexed(quantized.indices(), quantized.width(), quantized.height(),
                quantized.palette(), deflateEngine, profile, strategy);
    }

    /**
     * 有损格式压缩（质量可调）
     *
     * @param image   图像
     * @param format  目标格式名，如 {@code jpeg}、{@code webp}
     * @param options 压缩参数
     * @return 压缩后的字节
     * @throws IOException 当写出失败时
     */
    protected byte[] compressLossy(BufferedImage image, String format, CompressOptions options) throws IOException {
        BufferedImage prepared = image;
        if (isAlphaRequired(format)) {
            // jpeg / bmp 不支持 Alpha，先铺白底再编码，否则透明区会变黑
            prepared = flatten(image);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, image.getWidth() * image.getHeight() / 4));
             ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            if (stream == null) {
                throw new IOException("无法创建图片输出流");
            }
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
            if (!writers.hasNext()) {
                throw new IOException("当前运行环境未注册该格式的写出器: " + format);
            }
            ImageWriter writer = writers.next();
            try {
                writer.setOutput(stream);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(options.quality() / 100F);
                }
                writer.write(null, new IIOImage(prepared, null, null), param);
            } finally {
                writer.dispose();
            }
            stream.flush();
            return out.toByteArray();
        }
    }

    /**
     * JPEG 压缩：优先走 {@code ImageEncoder} SPI（本仓库已有 libjpeg-turbo 原生实现），
     * 原生库不可用时回退到 ImageIO 的纯 Java 写出器。
     *
     * @param image   图像
     * @param options 压缩参数
     * @return JPEG 字节
     * @throws IOException 当两条路径都失败时
     */
    protected byte[] compressJpeg(BufferedImage image, CompressOptions options) throws IOException {
        try {
            ImageEncoder encoder = ServiceProvider.of(ImageEncoder.class).getPriority();
            if (encoder != null && encoder.supports("jpeg")) {
                return encoder.encode(flatten(image), options.quality() / 100F);
            }
        } catch (Exception e) {
            log.warn("[图片压缩] 原生 JPEG 编码器不可用，回退 ImageIO: {}", e.getMessage());
        }
        return compressLossy(image, "jpeg", options);
    }

    /**
     * 目标格式是否需要保留 Alpha 通道
     *
     * @param format 格式名
     * @return 需要保留返回 {@code true}
     */
    protected boolean isAlphaRequired(String format) {
        return "png".equals(format) || "gif".equals(format) || "webp".equals(format)
                || "tif".equals(format) || "tiff".equals(format) || "avif".equals(format)
                || "jxl".equals(format) || "heic".equals(format) || "heif".equals(format);
    }

    /**
     * 把带 Alpha 的图像铺到白底上，转换为不透明图像
     *
     * @param image 源图像
     * @return 不透明图像
     */
    protected BufferedImage flatten(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return image;
        }
        BufferedImage opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = opaque.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return opaque;
    }

    /**
     * 标准化格式名
     *
     * @param format 原始格式名
     * @return 小写格式名；{@code null} 原样返回
     */
    protected String normalize(String format) {
        if (format == null) {
            return null;
        }
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "jpeg";
            case "tif", "tiff" -> "tiff";
            case "heic", "heif" -> "heic";
            default -> format.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 把输入流读完为字节数组
     *
     * @param input 输入流，不会在本方法内关闭
     * @return 字节数组
     * @throws IOException 当读取失败时
     */
    protected byte[] readFully(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("输入流不能为空");
        }
        try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16)) {
            stream.transferTo(out);
            return out.toByteArray();
        }
    }
}