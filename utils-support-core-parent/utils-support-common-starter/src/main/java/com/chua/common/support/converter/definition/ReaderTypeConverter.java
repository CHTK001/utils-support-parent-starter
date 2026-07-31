package com.chua.common.support.converter.definition;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Reader 类型转换器。
 * <p>将各种类型的值转换为 {@link Reader}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link Reader} — 直接返回</li>
 *   <li>{@link File} / {@link FileDescriptor} / {@link Path} — 通过 FileReader 读取</li>
 *   <li>{@link URL} / {@link URI} / {@link InputStream} — 转为 InputStreamReader</li>
 *   <li>{@link String} — 通过 StringReader 读取</li>
 *   <li>{@code char[]} — 通过 CharArrayReader 读取</li>
 *   <li>{@code byte[]} — 通过 InputStreamReader + ByteArrayInputStream 读取</li>
 *   <li>{@link PipedWriter} — 通过 PipedReader 连接</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2021/5/24
 */
public class ReaderTypeConverter implements TypeConverter<Reader> {

    private static final ReaderTypeConverter INSTANCE = new ReaderTypeConverter();

    /**
     * 将给定值转换为 Reader。
     *
     * @param value 源值
     * @return Reader 值，如果无法转换则返回 null
     */
    @Override
    public Reader convert(Object value) {
        if (null == value) {
            return null;
        }

        if (value instanceof Reader) {
            return (Reader) value;
        }

        if (value instanceof File) {
            try {
                return new FileReader((File) value);
            } catch (FileNotFoundException ignored) {
            }
        }

        if (value instanceof FileDescriptor) {
            return new FileReader((FileDescriptor) value);
        }

        if (value instanceof Path) {
            try {
                return new FileReader(((Path) value).toFile());
            } catch (FileNotFoundException ignored) {
            }
        }


        if (value instanceof URL) {
            try {
                return INSTANCE.convert(((URL) value).openStream());
            } catch (Exception ignored) {
            }
        }

        if (value instanceof URI) {
            try {
                return INSTANCE.convert(((URI) value).toURL().openStream());
            } catch (Exception ignored) {
            }
        }

        if (value instanceof InputStream) {
            return new InputStreamReader((InputStream) value, UTF_8);
        }

        if (value instanceof String) {
            return new StringReader(value.toString());
        }

        if (value instanceof char[]) {
            return new CharArrayReader(((char[]) value));
        }

        if (value instanceof byte[]) {
            return new InputStreamReader(new ByteArrayInputStream(((byte[]) value)));
        }

        if (value instanceof PipedWriter) {
            try {
                return new PipedReader(((PipedWriter) value));
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Reader.class
     */
    @Override
    public Class<Reader> getType() {
        return Reader.class;
    }
}
