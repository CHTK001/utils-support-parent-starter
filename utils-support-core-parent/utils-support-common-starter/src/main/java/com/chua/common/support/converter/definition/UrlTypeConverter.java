package com.chua.common.support.converter.definition;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NullUnmarked;


/**
 * URL 类型转换器。
 * <p>将各种类型的值转换为 {@link URL}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link URL} — 直接返回</li>
 *   <li>{@link File} — 通过 toURI().toURL() 转换</li>
 *   <li>{@link Path} — 通过 toUri().toURL() 转换</li>
 *   <li>{@link URI} — 通过 toURL() 转换</li>
 *   <li>{@link String} — 支持 http://、https://、file: 前缀的 URL 字符串，也支持本地文件路径</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/11/26
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class UrlTypeConverter implements TypeConverter<URL> {

    private static final String HTTP_PROTOCOL = "http";
    private static final String HTTPS_PROTOCOL = "https";
    private static final String FILE_URL_PREFIX = "file:";

    /**
     * 将给定值转换为 URL。
     *
     * @param value 源值
     * @return URL 值，如果无法转换则返回 null
     */
    @Override
    public URL convert(Object value) {
        if (null == value) {
            return null;
        }

        if (value instanceof URL) {
            return (URL) value;
        }

        if (value instanceof File) {
            try {
                return ((File) value).toURI().toURL();
            } catch (MalformedURLException ignored) {
            }
        }

        if (value instanceof Path) {
            try {
                return ((Path) value).toUri().toURL();
            } catch (MalformedURLException ignored) {
            }
        }

        if (value instanceof URI) {
            try {
                return ((URI) value).toURL();
            } catch (MalformedURLException ignored) {
            }
        }

        if (value instanceof String) {
            String str = value.toString();
            try {
                if(str.startsWith(HTTP_PROTOCOL) || str.startsWith(HTTPS_PROTOCOL) || str.startsWith(FILE_URL_PREFIX)) {
                    return new URL(str);
                }

                File file = new File(str);
                if (file.exists()) {
                    return file.toURI().toURL();
                }
            } catch (MalformedURLException e1) {
                try {
                    return new URL(value.toString());
                } catch (MalformedURLException ignored) {
                }
            }
        }

        return null;
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return URL.class
     */
    @Override
    public Class<URL> getType() {
        return URL.class;
    }
}
