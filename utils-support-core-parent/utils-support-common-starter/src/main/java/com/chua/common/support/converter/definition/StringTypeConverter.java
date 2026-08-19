package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;


/**
 * String 类型转换器。
 * <p>将各种类型的值转换为 {@link String}，支持以下类型的转换：</p>
 * <ul>
 *   <li>{@link java.util.Collection} / {@link java.util.Map} — 直接调用 toString()</li>
 *   <li>{@link File} — 返回绝对路径</li>
 *   <li>{@link java.nio.file.Path} — 返回绝对路径</li>
 *   <li>{@link java.net.URI} / {@link URL} — 返回字符串表示</li>
 *   <li>{@code byte[]} — 按 UTF-8 编码转为字符串</li>
 *   <li>对象数组 — 逗号拼接各元素 toString()</li>
 *   <li>{@link java.io.InputStream} — 读取全部字节后转为 UTF-8 字符串</li>
 *   <li>{@link Class} — 返回类型全名</li>
 *   <li>其他类型 — 直接调用 toString()</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/11/5
 */
public class StringTypeConverter implements TypeConverter<String> {

    @Override
    /** 获取Type */
    public Class<String> getType() {
        return String.class;
    }

    /**
     * 将给定值转换为 String。
     *
     * @param value 源值
     * @return String 值，如果为 null 则返回 null
     */
    @Override
    public String convert(Object value) {
        if (null == value) {
            return null;
        }

        if (value instanceof Collection) {
            return value.toString();
        }

        if (value instanceof Map) {
            return value.toString();
        }

        if (value instanceof File f) {
            return f.getAbsolutePath();
        }

        if(value instanceof Path path) {
            return path.toAbsolutePath().toString();
        }

        if(value instanceof URI uri) {
            return uri.toString();
        }

        if (value instanceof URL url) {
            return url.toExternalForm();
        }

        if (value.getClass().isArray()) {
            if (value instanceof byte[]) {
                return new String((byte[]) value, StandardCharsets.UTF_8);
            }

            if (value instanceof Object[] && Array.getLength(value) == 1) {
                Object o = Array.get(value, 0);
                if (o instanceof byte[]) {
                    return new String((byte[]) o, StandardCharsets.UTF_8);
                }
                if (o instanceof String) {
                    return (String) o;
                }
            }

            // Join array elements with comma
            StringBuilder sb = new StringBuilder();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(Array.get(value, i));
            }
            String join = sb.toString();
            return "".equalsIgnoreCase(join) ? null : join;
        }

        if (value instanceof InputStream) {
            try {
                return StringUtils.utf8Str(readBytes((InputStream) value));
            } catch (IOException ignored) {
            }
        }

        if (value instanceof Class<?>) {
            return ((Class<?>) value).getTypeName();
        }
        return value.toString();
    }

    /**
     * 从 InputStream 中读取全部字节。
     *
     * @param is 输入流
     * @return 字节数组
     * @throws IOException 读取异常
     */
    private byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }
}
