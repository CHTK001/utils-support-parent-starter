package com.chua.common.support.converter.definition;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Path 类型转换器。
* <p>将各种类型的值转换为 {@link Path}，支持以下输入类型：</p>
* <ul>
*   <li>{@link Path} — 直接返回</li>
*   <li>{@link File} — 通过 toPath() 转换</li>
*   <li>{@link String} — 通过 Paths.get() 解析</li>
*   <li>{@link URL} / {@link URI} — 转为 Path</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2021/5/24
 */
public class PathTypeConverter implements TypeConverter<Path> {

    /**
    * 将给定值转换为 Path。
    *
    * @param value 源值
    * @return Path 值，如果无法转换则返回 null
     */
    @Override
    public Path convert(Object value) {
        if (null == value) {
            return null;
        }
        if (value instanceof Path) {
            return (Path) value;
        }

        if (value instanceof File) {
            return ((File) value).toPath();
        }

        if (value instanceof String) {
            try {
                return Paths.get(value.toString());
            } catch (Exception ignored) {
            }
        }

        if (value instanceof URL) {
            try {
                return Paths.get(((URL) value).toURI());
            } catch (URISyntaxException ignored) {
            }
        }

        if (value instanceof URI) {
            return Paths.get((URI) value);
        }
        return null;
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Path.class
     */
    @Override
    public Class<Path> getType() {
        return Path.class;
    }
}
