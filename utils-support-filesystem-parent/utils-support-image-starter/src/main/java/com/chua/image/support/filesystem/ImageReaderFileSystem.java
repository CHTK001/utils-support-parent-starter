package com.chua.image.support.filesystem;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.file.converter.AbstractReader;
import com.chua.common.support.file.converter.ImageOperation;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * 图片读取文件系统
 * <p>
 * 支持图片文件的读取和操作：
 * - 读取图片信息（宽度、高度、格式、颜色模式等）
 * - 图片缩放（链式操作）
 * - 图片裁剪（链式操作）
 * - 图片旋转（链式操作）
 * - 图片滤镜（灰度、模糊、亮度等，链式操作）
 * - 图片压缩（链式操作）
 * </p>
 * <p>
 * 支持的图片格式：
 * - JPEG (.jpg, .jpeg)
 * - PNG (.png)
 * - BMP (.bmp)
 * - GIF (.gif)
 * - WEBP (.webp)
 * - TIFF (.tiff, .tif)
 * - ICO (.ico)
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * ImageReaderFileSystem imageFs = new ImageReaderFileSystem("image.jpg");
 * // 链式操作：缩放 -> 旋转 -> 压缩
 * imageFs.resize(800, 600)
 *        .rotate(90)
 *        .compress(0.8f, "jpg", new File("output.jpg"));
 * </pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"jpg", "jpeg", "png", "bmp", "webp", "tiff", "tif", "ico", "apng", "heic", "heif", "cr2", "nef", "arw", "raf", "orf", "rw2", "image"})
public class ImageReaderFileSystem extends AbstractReader {

    /**
     * 支持的图片格式扩展名（动态获取）
     */
    private static Set<String> getSupportedExtensions() {
        var extensions = new HashSet<String>();
        extensions.addAll(Set.of(".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".tiff", ".tif", ".ico", ".heic", ".heif"));
        return Set.copyOf(extensions);
    }

    /**
     * 默认类型
     */
    private static final String DEFAULT_TYPE = "image";

    /**
     * 图片操作实现（通过SPI加载）
     */
    private ImageOperation imageOperation;

    /**
     * 当前处理的图片（链式操作）
     */
    private BufferedImage currentImage;

    /**
     * 默认构造函数
     */
    public ImageReaderFileSystem() {
        super();
        initImageOperation();
    }

    /**
     * 构造函数
     *
     * @param file 文件对象
     */
    public ImageReaderFileSystem(File file) {
        super(file);
        initImageOperation();
    }

    /**
     * 构造函数
     *
     * @param filePath 文件路径
     */
    public ImageReaderFileSystem(String filePath) {
        super(filePath);
        initImageOperation();
    }

    /**
     * 初始化图片操作实现
     */
    private void initImageOperation() {
        if (imageOperation == null) {
            imageOperation = ServiceProvider.of(ImageOperation.class).getExtension();
            if (imageOperation == null) {
                throw new IllegalStateException("未找到图片操作实现，请确保有ImageOperation的SPI实现");
            }
        }
    }

    @Override
    /** 获取Type */
    public String getType() {
        if (file == null) {
            return DEFAULT_TYPE;
        }
        var fileName = file.getName().toLowerCase();
        var extension = getExtension(fileName);
        var supportedExtensions = getSupportedExtensions();
        if (supportedExtensions.contains(extension)) {
            return extension.substring(1);
        }
        return DEFAULT_TYPE;
    }

    /** 是否Support */
    public boolean isSupport(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return false;
        }
        var fileName = file.getName().toLowerCase();
        var supportedExtensions = getSupportedExtensions();
        return supportedExtensions.stream().anyMatch(fileName::endsWith);
    }

    @Override
    /** WithFile */
    public ImageReaderFileSystem withFile(File file) {
        super.withFile(file);
        return this;
    }

    /**
     * 获取文件扩展名
     *
     * @param fileName 文件名
     * @return 扩展名（包含点号）
     */
    private String getExtension(String fileName) {
        var lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot) : "";
    }

    @Override
    /** Do读取Maps */
    protected List<Map<String, Object>> doReadMaps() throws IOException {
        if (file == null) {
            throw new IOException("文件对象为null");
        }
        if (!file.exists()) {
            log.warn("[图片读取文件系统][读取]文件不存在: {}", file.getAbsolutePath());
            return new ArrayList<>();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> imageInfo = new LinkedHashMap<>();

        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(new FileInputStream(file))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                log.warn("[图片读取文件系统][读取]无法读取图片文件: {}", file.getAbsolutePath());
                return result;
            }

            ImageReader reader = readers.next();
            reader.setInput(imageInputStream);

            // 读取图片基本信息
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            int imageCount = reader.getNumImages(true);

            imageInfo.put("width", width);
            imageInfo.put("height", height);
            imageInfo.put("imageCount", imageCount);
            imageInfo.put("format", getImageFormat());
            imageInfo.put("fileSize", file.length());
            imageInfo.put("fileName", file.getName());
            imageInfo.put("filePath", file.getAbsolutePath());

            // 读取图片类型信息
            try {
                BufferedImage bufferedImage = reader.read(0);
                if (bufferedImage != null) {
                    imageInfo.put("imageType", bufferedImage.getType());
                    imageInfo.put("colorModel", bufferedImage.getColorModel().getClass().getSimpleName());
                    imageInfo.put("hasAlpha", bufferedImage.getColorModel().hasAlpha());
                    imageInfo.put("transparency", bufferedImage.getTransparency());
                }
            } catch (Exception e) {
                log.debug("[图片读取文件系统][读取]读取图片类型信息失败", e);
            }

            reader.dispose();
        } catch (Exception e) {
            log.error("[图片读取文件系统][读取]读取图片文件失败: {}", file.getAbsolutePath(), e);
            throw new IOException("读取图片文件失败: " + file.getAbsolutePath(), e);
        }

        result.add(imageInfo);
        log.debug("[图片读取文件系统][读取]文件读取成功: {}", file.getAbsolutePath());
        return result;
    }

    /**
     * 获取图片格式
     *
     * @return 图片格式
     */
    private String getImageFormat() {
        if (file == null) {
            return "unknown";
        }
        var fileName = file.getName().toLowerCase();
        var extension = getExtension(fileName);
        if (extension.isEmpty()) {
            return "unknown";
        }
        return extension.substring(1);
    }

    /**
     * 获取当前图片（如果未加载则从文件加载）
     *
     * @return 当前图片
     * @throws IOException IO异常
     */
    private BufferedImage getCurrentImage() throws IOException {
        if (currentImage == null) {
            if (file == null || !file.exists()) {
                throw new IOException("文件对象为null或文件不存在");
            }
            currentImage = BufferedImageUtils.toBufferedImage(file);
        }
        return currentImage;
    }

    /**
     * 缩放图片（链式操作）
     *
     * @param width  目标宽度（null表示保持比例）
     * @param height 目标高度（null表示保持比例）
     * @return this
     * @throws IOException IO异常
     */
    public ImageReaderFileSystem resize(Integer width, Integer height) throws IOException {
        var image = getCurrentImage();
        var format = getImageFormat();
        var imageBytes = BufferedImageUtils.toBufferedImageArray(image, format);
        var resultBytes = imageOperation.resize(imageBytes, format, width, height, null).block();
        currentImage = BufferedImageUtils.getBufferedImage(resultBytes);
        return this;
    }

    /**
     * 按比例缩放图片（链式操作）
     *
     * @param scale 缩放比例（大于1放大，小于1缩小）
     * @return this
     * @throws IOException IO异常
     */
    public ImageReaderFileSystem resize(double scale) throws IOException {
        var image = getCurrentImage();
        var format = getImageFormat();
        var imageBytes = BufferedImageUtils.toBufferedImageArray(image, format);
        var resultBytes = imageOperation.resize(imageBytes, format, null, null, scale).block();
        currentImage = BufferedImageUtils.getBufferedImage(resultBytes);
        return this;
    }

    /**
     * 裁剪图片（链式操作）
     *
     * @param x      裁剪起始X坐标
     * @param y      裁剪起始Y坐标
     * @param width  裁剪宽度
     * @param height 裁剪高度
     * @return this
     * @throws IOException IO异常
     */
    public ImageReaderFileSystem crop(int x, int y, int width, int height) throws IOException {
        var image = getCurrentImage();
        var format = getImageFormat();
        var imageBytes = BufferedImageUtils.toBufferedImageArray(image, format);
        var resultBytes = imageOperation.crop(imageBytes, format, x, y, width, height).block();
        currentImage = BufferedImageUtils.getBufferedImage(resultBytes);
        return this;
    }

    /**
     * 旋转图片（链式操作）
     *
     * @param angle 旋转角度（度数，正数顺时针，负数逆时针）
     * @return this
     * @throws IOException IO异常
     */
    public ImageReaderFileSystem rotate(int angle) throws IOException {
        var image = getCurrentImage();
        var format = getImageFormat();
        var imageBytes = BufferedImageUtils.toBufferedImageArray(image, format);
        var resultBytes = imageOperation.rotate(imageBytes, format, angle).block();
        currentImage = BufferedImageUtils.getBufferedImage(resultBytes);
        return this;
    }

    /**
     * 压缩并保存图片（链式操作的终点）
     *
     * @param quality 压缩质量（0.0-1.0）
     * @param format  输出格式
     * @param output  输出文件
     * @return this
     * @throws IOException IO异常
     */
    public ImageReaderFileSystem compress(float quality, String format, File output) throws IOException {
        var image = getCurrentImage();
        var inputFormat = getImageFormat();
        var imageBytes = BufferedImageUtils.toBufferedImageArray(image, inputFormat);
        imageOperation.compress(imageBytes, inputFormat, quality, format, output).block();
        // 重置当前图片，以便下次操作重新加载
        currentImage = null;
        return this;
    }

    /**
     * 保存图片（链式操作的终点）
     *
     * @param output 输出文件
     * @return this
     * @throws IOException IO异常
     */
    public ImageReaderFileSystem save(File output) throws IOException {
        var format = getImageFormat();
        BufferedImageUtils.writeToFile(getCurrentImage(), format, output);
        // 重置当前图片，以便下次操作重新加载
        currentImage = null;
        return this;
    }

    /**
     * 保存图片（链式操作的终点）
     *
     * @param outputPath 输出文件路径
     * @return this
     * @throws IOException IO异常
     */
    public ImageReaderFileSystem save(String outputPath) throws IOException {
        return save(new File(outputPath));
    }
}

