package com.chua.image.support.utils;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 图片格式支持工具类。
 *
 * <p>提供对 Java Image I/O 框架支持的图片格式查询和转换能力。
 * 动态查询当前 JVM 中已注册的 {@link javax.imageio.spi.IIOServiceProvider}，
 * 无需硬编码格式列表，自动适配不同 JDK 版本和第三方 SPI 插件。
 *
 * <h3>核心方法</h3>
 * <ul>
 *   <li>{@link #supportReader()} — 获取所有可读取的图片格式名称</li>
 *   <li>{@link #supportWriter()} — 获取所有可写入的图片格式名称</li>
 *   <li>{@link #saveToStream(BufferedImage, String, OutputStream)} — 保存图片到输出流，自动处理格式差异</li>
 *   <li>{@link #normalizeFormat(String)} — 标准化格式名称（如 jpg → jpeg, tif → tiff）</li>
 * </ul>
 *
 * <h3>格式说明</h3>
 * <p>Java 标准 ImageIO 通常支持以下格式：</p>
 * <ul>
 *   <li><b>JPEG</b> (.jpg, .jpeg) — 有损压缩，不含 Alpha 通道</li>
 *   <li><b>PNG</b> (.png) — 无损压缩，支持 Alpha 通道</li>
 *   <li><b>BMP</b> (.bmp) — 无压缩，文件较大</li>
 *   <li><b>GIF</b> (.gif) — 索引色，支持动画</li>
 *   <li><b>WBMP</b> (.wbmp) — 无线应用协议位图</li>
 *   <li><b>TIFF</b> (.tiff, .tif) — 需 JAI ImageIO 插件</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ImageSupportUtils {

    /**
     * 格式别名映射：别名 → 标准名称
     */
    private static final Map<String, String> FORMAT_ALIASES = new LinkedHashMap<>();

    /**
     * 需要排除的格式名称集合（由专门的转换器处理）
     */
    private static final Set<String> EXCLUDED_FORMATS = Set.of(
            "webp", "raw", "cr2", "nef", "arw", "raf", "orf", "rw2", "dng"
    );

    static {
        FORMAT_ALIASES.put("jpg", "jpeg");
        FORMAT_ALIASES.put("jpeg", "jpeg");
        FORMAT_ALIASES.put("tif", "tiff");
        FORMAT_ALIASES.put("tiff", "tiff");
        FORMAT_ALIASES.put("png", "png");
        FORMAT_ALIASES.put("bmp", "bmp");
        FORMAT_ALIASES.put("gif", "gif");
        FORMAT_ALIASES.put("wbmp", "wbmp");
        FORMAT_ALIASES.put("ico", "ico");
    }

    private ImageSupportUtils() {
    }

    /**
     * 获取 ImageIO 支持的所有可读取图片格式名称。
     *
     * <p>通过 {@link IIORegistry} 获取所有已注册的 {@link ImageReaderSpi}，
     * 收集它们声明的文件后缀名。返回的格式名称已去重且均转为小写。
     *
     * @return 可读取的图片格式名称数组，不会返回 {@code null}
     */
    public static String[] supportReader() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        Iterator<ImageReaderSpi> providers = registry.getServiceProviders(ImageReaderSpi.class, false);

        Set<String> suffixes = new LinkedHashSet<>();
        providers.forEachRemaining(spi -> {
            String[] formatNames = spi.getFormatNames();
            for (String name : formatNames) {
                suffixes.add(name.toLowerCase());
            }
            // 也加入文件后缀名
            String[] fileSuffixes = spi.getFileSuffixes();
            for (String suffix : fileSuffixes) {
                if (suffix != null && !suffix.isEmpty()) {
                    suffixes.add(suffix.toLowerCase());
                }
            }
        });

        return suffixes.toArray(new String[0]);
    }

    /**
     * 获取 ImageIO 支持的所有可写入图片格式名称。
     *
     * <p>通过 {@link IIORegistry} 获取所有已注册的 {@link ImageWriterSpi}，
     * 收集它们声明的格式名称。返回的格式名称已去重且均转为小写。
     *
     * @return 可写入的图片格式名称数组，不会返回 {@code null}
     */
    public static String[] supportWriter() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        Iterator<ImageWriterSpi> providers = registry.getServiceProviders(ImageWriterSpi.class, false);

        Set<String> suffixes = new LinkedHashSet<>();
        providers.forEachRemaining(spi -> {
            String[] formatNames = spi.getFormatNames();
            for (String name : formatNames) {
                suffixes.add(name.toLowerCase());
            }
            String[] fileSuffixes = spi.getFileSuffixes();
            for (String suffix : fileSuffixes) {
                if (suffix != null && !suffix.isEmpty()) {
                    suffixes.add(suffix.toLowerCase());
                }
            }
        });

        return suffixes.toArray(new String[0]);
    }

    /**
     * 判断指定格式是否可读取。
     *
     * @param format 格式名称（如 "png"、"jpg"）
     * @return 如果可以读取返回 {@code true}
     */
    public static boolean isReadable(String format) {
        if (format == null) {
            return false;
        }
        String normalized = normalizeFormat(format);
        return ImageIO.getImageReadersByFormatName(normalized).hasNext();
    }

    /**
     * 判断指定格式是否可写入。
     *
     * @param format 格式名称（如 "png"、"jpg"）
     * @return 如果可以写入返回 {@code true}
     */
    public static boolean isWritable(String format) {
        if (format == null) {
            return false;
        }
        String normalized = normalizeFormat(format);
        return ImageIO.getImageWritersByFormatName(normalized).hasNext();
    }

    /**
     * 判断是否为需要排除的格式（由专用转换器处理）。
     *
     * @param format 格式名称
     * @return 如果是排除格式返回 {@code true}
     */
    public static boolean isExcluded(String format) {
        if (format == null) {
            return false;
        }
        return EXCLUDED_FORMATS.contains(format.toLowerCase());
    }

    /**
     * 标准化图片格式名称。
     *
     * <p>处理格式别名和大小写差异：</p>
     * <ul>
     *   <li>{@code "jpg"} → {@code "jpeg"}</li>
     *   <li>{@code "tif"} → {@code "tiff"}</li>
     *   <li>{@code "JPG"}、{@code "Jpeg"} → {@code "jpeg"}</li>
     * </ul>
     *
     * @param format 原始格式名称
     * @return 标准化后的格式名称，如果未找到别名则返回原名称的小写形式
     */
    public static String normalizeFormat(String format) {
        if (format == null || format.isEmpty()) {
            return "png";
        }
        String lower = format.toLowerCase();
        return FORMAT_ALIASES.getOrDefault(lower, lower);
    }

    /**
     * 保存 BufferedImage 到输出流，自动处理格式差异。
     *
     * <p>特殊处理：</p>
     * <ul>
     *   <li><b>JPEG</b> — 不支持 Alpha 通道，自动将 ARGB 转换为 RGB；设置最高质量</li>
     *   <li><b>ICO</b> — 不做特殊处理（标准 ImageIO 通常不写 ICO，由专用转换器处理）</li>
     * </ul>
     *
     * @param image        待保存的图片
     * @param format       目标格式名称
     * @param outputStream 目标输出流
     * @throws IOException 写入失败
     */
    public static void saveToStream(BufferedImage image, String format, OutputStream outputStream) throws IOException {
        String normalizedFormat = normalizeFormat(format);

        if ("jpeg".equals(normalizedFormat)) {
            // JPEG：需移除 Alpha 通道，设置最高压缩质量
            BufferedImage rgbImage = toRgbImage(image);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.95f);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(rgbImage, null, null), param);
                writer.dispose();
            }
            return;
        }

        // 其他格式：直接写入
        ImageIO.write(image, normalizedFormat, outputStream);
    }

    /**
     * 将可能包含 Alpha 通道的图片转换为 RGB 格式（不含 Alpha）。
     *
     * <p>用于 JPEG 等不支持透明通道的格式。</p>
     *
     * @param image 源图片
     * @return RGB 格式的图片
     */
    public static BufferedImage toRgbImage(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB
                || image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = rgb.createGraphics();
        g2d.drawImage(image, 0, 0, null);
        g2d.dispose();
        return rgb;
    }

    /**
     * 获取所有支持的格式名称集合（包含别名，不包含排除格式）。
     *
     * @return 去重的标准化格式名称集合
     */
    public static Set<String> getAllSupportedFormats() {
        Set<String> formats = new LinkedHashSet<>();
        Stream.of(supportReader(), supportWriter())
                .flatMap(Arrays::stream)
                .map(ImageSupportUtils::normalizeFormat)
                .filter(f -> !isExcluded(f))
                .forEach(formats::add);
        return formats;
    }

    /**
     * 从文件扩展名推断图片格式。
     *
     * @param file 文件对象
     * @return 标准化后的格式名称，无法推断时返回 "png"
     */
    public static String getFormatFromFile(File file) {
        if (file == null) {
            return "png";
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1);
            return normalizeFormat(ext);
        }
        return "png";
    }
}
