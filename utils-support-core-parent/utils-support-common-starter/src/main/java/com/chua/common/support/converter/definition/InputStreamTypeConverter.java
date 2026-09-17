package com.chua.common.support.converter.definition;

import com.chua.common.support.converter.Converter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* InputStream 类型转换器。
* <p>将各种类型的值转换为 {@link InputStream}，支持以下输入类型：</p>
* <ul>
*   <li>{@link java.awt.image.BufferedImage} — 写入 JPEG 格式的 ByteArrayInputStream</li>
*   <li>{@link File} / {@link java.nio.file.Path} — 通过 FileInputStream 读取</li>
*   <li>{@link URL} / {@link URI} — 通过 openStream() 获取</li>
*   <li>{@link FileDescriptor} — 通过 FileInputStream 读取</li>
*   <li>{@link Reader} — 读取全部字符后转为 UTF-8 字节流</li>
*   <li>{@link String} — 尝试作为文件路径读取，失败则通过 File 转换器获取</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2021/5/24
 */
public class InputStreamTypeConverter implements TypeConverter<InputStream> {

    /**
    * 将给定值转换为 InputStream。
    *
    * @param value 源值
    * @return InputStream 值，如果无法转换则返回 null
    */
    @Override
    public InputStream convert(Object value) {
        if (null == value) {
            return null;
        }

        if (value instanceof BufferedImage) {
            try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
                ImageIO.write((RenderedImage) value, "jpg", byteArrayOutputStream);
                return new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
            } catch (IOException ignored) {
            }
            return null;
        }

        if (value instanceof File) {
            try {
                return new FileInputStream((File) value);
            } catch (FileNotFoundException ignored) {
            }
        }

        if (value instanceof Path) {
            try {
                return new FileInputStream(((Path) value).toFile());
            } catch (FileNotFoundException ignored) {
            }
        }


        if (value instanceof URL) {
            try {
                return ((URL) value).openStream();
            } catch (Exception ignored) {
            }
        }

        if (value instanceof URI) {
            try {
                return ((URI) value).toURL().openStream();
            } catch (Exception ignored) {
            }
        }

        if (value instanceof FileDescriptor) {
            return new FileInputStream((FileDescriptor) value);
        }

        if (value instanceof Reader) {
            Reader reader = (Reader) value;
            try {
                StringWriter sw = new StringWriter();
                char[] buffer = new char[8192];
                int len;
                while ((len = reader.read(buffer)) != -1) {
                    sw.write(buffer, 0, len);
                }
                byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
                return new ByteArrayInputStream(bytes);
            } catch (IOException ignored) {
            }
        }

        if (value instanceof String) {
            String s = value.toString();
            File file = new File(s);
            if(file.exists()) {
                try {
                    return new FileInputStream(file);
                } catch (FileNotFoundException ignored) {
                }
            }
            try {
                return Files.newInputStream(Converter.convertIfNecessary(value, File.class).toPath());
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return InputStream.class
    */
    @Override
    public Class<InputStream> getType() {
        return InputStream.class;
    }
}
