package com.chua.common.support.converter.definition;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * ByteSource 类型转换器（转换为 {@code byte[]}）。
 * <p>将各种类型的值转换为 {@code byte[]}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@code byte[]} — 直接返回</li>
 *   <li>{@link InputStream} / {@link Path} / {@link File} — 读取全部字节</li>
 *   <li>{@link String} — 先尝试作为文件路径读取，失败则按 UTF-8 编码</li>
 *   <li>{@link URI} / {@link URL} — 下载全部字节</li>
 * </ul>
 *
 * @author CH
 * @since 2024/5/16
 */
public class ByteSourceTypeConverter implements TypeConverter<byte[]> {
    @Override
    /** 获取Type */
    public Class<byte[]> getType() {
        return byte[].class;
    }

    /**
    * 将给定值转换为 byte[]。
    *
    * @param value 源值
    * @return byte[] 值，如果无法转换则返回 null
    */
    @Override
    public byte[] convert(Object value) {
        if(value instanceof byte[]) {
            return (byte[]) value;
        }

        if(value instanceof InputStream) {
            try {
                return readBytes((InputStream) value);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        if(value instanceof Path) {
            try {
                return java.nio.file.Files.readAllBytes((Path) value);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        if(value instanceof File) {
            try {
                return java.nio.file.Files.readAllBytes(((File) value).toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        if(value instanceof String) {
            try {
                return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get((String) value));
            } catch (Exception e) {
                return ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        if(value instanceof URI) {
            try {
                try (InputStream is = ((URI) value).toURL().openStream()) {
                    return readBytes(is);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        if(value instanceof URL) {
            try {
                try (InputStream is = ((URL) value).openStream()) {
                    return readBytes(is);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return null;
    }

    /**
     * 从 InputStream 中读取全部字节。
     *
     * @param is 输入流
     * @return 字节数组
     * @throws IOException 读取异常
     */
    private byte[] readBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }
}
