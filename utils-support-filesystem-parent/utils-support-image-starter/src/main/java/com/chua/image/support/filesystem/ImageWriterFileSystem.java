package com.chua.image.support.filesystem;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.file.converter.AbstractWriter;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;

/**
 * 图片写入文件系统
 * <p>
 * - bytes 写入：直接覆盖写入文件；
 * - Map 写入：支持以下字段（优先级从高到低）：
 *   - bytes: byte[]
 *   - base64: String（Base64）
 *   - image: BufferedImage
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"jpg", "jpeg", "png", "bmp", "gif", "webp", "tiff", "tif", "ico", "apng", "heic", "heif", "cr2", "nef", "arw", "raf", "orf", "rw2", "image"})
public class ImageWriterFileSystem extends AbstractWriter {

    /** Key_bytes */
    private static final String KEY_BYTES = "bytes";
    /** Key_base64 */
    private static final String KEY_BASE64 = "base64";
    /** Key_image */
    private static final String KEY_IMAGE = "image";

    public ImageWriterFileSystem() {
        super();
    }

    public ImageWriterFileSystem(File file) {
        super(file);
    }

    public ImageWriterFileSystem(String filePath) {
        super(filePath);
    }

    @Override
    /** 获取Type */
    public String getType() {
        
        return "image";
    
    }

    /** 是否Support */
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        if (file.exists() && file.isDirectory()) {
            return false;
        }
        var name = file.getName().toLowerCase();
        return name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".bmp")
                || name.endsWith(".gif")
                || name.endsWith(".webp")
                || name.endsWith(".tiff")
                || name.endsWith(".tif")
                || name.endsWith(".ico")
                || name.endsWith(".apng")
                || name.endsWith(".heic")
                || name.endsWith(".heif")
                || name.endsWith(".cr2")
                || name.endsWith(".nef")
                || name.endsWith(".arw")
                || name.endsWith(".raf")
                || name.endsWith(".orf")
                || name.endsWith(".rw2");
    }

    @Override
    /** Do初始化 */
    protected void doInitialize() throws IOException {
        if (file == null) {
            throw new IOException("文件对象为null");
        }
        if (file.getParentFile() != null) {
            Files.createDirectories(file.getParentFile().toPath());
        }
    }

    @Override
    /** Do写入Line */
    protected void doWriteLine(String line) throws IOException {
        throw new UnsupportedOperationException("图片文件系统不支持按行写入");
    }

    @Override
    /** Do写入Text */
    protected void doWriteText(String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        // 尝试按 base64 写入
        doWrite(Map.of(KEY_BASE64, text));
    }

    @Override
    /** Do写入Bytes */
    protected void doWriteBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        Files.write(file.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    /** Do写入 */
    protected void doWrite(Map<String, Object> data) throws IOException {
        if (data == null || data.isEmpty()) {
            return;
        }

        var bytes = data.get(KEY_BYTES);
        if (bytes instanceof byte[] arr) {
            doWriteBytes(arr);
            return;
        }

        var base64 = data.get(KEY_BASE64);
        if (base64 != null) {
            try {
                doWriteBytes(Base64.getDecoder().decode(base64.toString()));
            } catch (Exception e) {
                log.error("[图片写入文件系统][写入]Base64解码失败", e);
                throw new IOException("Base64解码失败", e);
            }
            return;
        }

        var image = data.get(KEY_IMAGE);
        if (image instanceof BufferedImage bufferedImage) {
            BufferedImageUtils.writeToFile(bufferedImage, resolveFormat(), file);
            return;
        }

        throw new UnsupportedOperationException("图片文件系统不支持当前写入数据格式");
    }

    @Override
    /** Do刷新 */
    protected void doFlush() throws IOException {
        // 文件写入为一次性覆盖，flush 由底层 NIO 处理
    }

    @Override
    /** DoFinish */
    protected void doFinish() throws IOException {
        // 无需额外收尾
    }

    /** 解析格式化 */
    private String resolveFormat() {
        if (file == null) {
            return "png";
        }
        var name = file.getName().toLowerCase();
        var dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "png";
        }
        return name.substring(dot + 1);
    }
}



