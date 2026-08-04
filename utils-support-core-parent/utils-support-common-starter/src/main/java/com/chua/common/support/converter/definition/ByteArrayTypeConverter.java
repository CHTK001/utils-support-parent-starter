package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NullUnmarked;


/**
 * byte[] 类型转换器。
 * <p>将各种类型的值转换为 {@code byte[]}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link String} — 尝试作为文件读取；HTTP/HTTPS URL 下载；否则按 UTF-8 编码</li>
 *   <li>{@link Map} / {@link java.util.Collection} — toString().getBytes() 或遍历取 byteValue</li>
 *   <li>{@code byte[]} / {@link Byte} — 直接返回或包装</li>
 *   <li>{@link Short} / {@link Character} / {@link Integer} / {@link Long} / {@link Float} / {@link Double} / {@link Number} — 通过 ByteBuffer 转换</li>
 *   <li>{@link File} / {@link Path} / {@link URL} / {@link com.google.common.io.ByteSource} — 读取全部字节</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 */
@NullUnmarked
public class ByteArrayTypeConverter implements TypeConverter<byte[]> {


    /**
     * 将给定值转换为 byte[]。
     *
     * @param value 源值
     * @return byte[] 值，如果为 null 则返回空数组
     */
    @Override
    public byte[] convert(Object value) {
        if (null == value) {
            return new byte[0];
        }

        if(value instanceof String) {
            String string = value.toString();
            File file = new File(string);
            if(file.exists() && file.isFile()) {
                try {
                    return Files.readAllBytes(file.toPath());
                } catch (Exception ignored) {
                }
            }

            if(string.startsWith("http://") || string.startsWith("https://")) {
                try {
                    URL url = new URL(string);
                    try (InputStream is = url.openStream()) {
                        return readBytes(is);
                    }
                } catch (Exception ignored) {
                }
            }

            return StringUtils.utf8Bytes(value.toString());
        }

        if(value instanceof Map map) {
            return map.toString().getBytes(StandardCharsets.UTF_8);
        }

        if(value instanceof Collection collection) {
            byte[] bytes = new byte[collection.size()];
            int index = 0;
            for (Object object : collection) {
                bytes[index++] = transToBigDecimal(object).byteValue();
            }
            return bytes;
        }

        if(value instanceof byte[]) {
            return (byte[]) value;
        }

        if(value instanceof Byte) {
            return new byte[]{(byte) value};
        }

        if(value instanceof Short) {
            return ByteBuffer.allocate(Short.BYTES).putShort((Short) value).array();
        }

        if(value instanceof Character) {
            return ByteBuffer.allocate(Character.BYTES).putChar((Character) value).array();
        }

        if(value instanceof Integer) {
            return ByteBuffer.allocate(Integer.BYTES).putInt((Integer) value).array();
        }

        if(value instanceof Long) {
            return ByteBuffer.allocate(Long.BYTES).putLong((Long) value).array();
        }

        if(value instanceof Float) {
            return ByteBuffer.allocate(Float.BYTES).putFloat((Float) value).array();
        }

        if(value instanceof Double) {
            return ByteBuffer.allocate(Double.BYTES).putDouble((Double) value).array();
        }

        if(value instanceof Number) {
            return ByteBuffer.allocate(Long.BYTES).putLong(((Number) value).longValue()).array();
        }

        if(value instanceof File) {
            try (FileInputStream fis = new FileInputStream((File) value)) {
                return readBytes(fis);
            } catch (Exception ignored) {
            }
        }

        if(value instanceof Path) {
            try (InputStream fis = Files.newInputStream((Path) value)) {
                return readBytes(fis);
            } catch (Exception ignored) {
            }
        }
        if(value instanceof java.net.URL) {
            try (InputStream is = ((URL) value).openStream()) {
                return readBytes(is);
            } catch (Exception ignored) {
            }
        }


        if(value instanceof com.google.common.io.ByteSource) {
            try (InputStream fis = ((com.google.common.io.ByteSource) value).openStream()) {
                return readBytes(fis);
            } catch (Exception ignored) {
            }
        }

        return new byte[0];
    }

    /**
     * 从 InputStream 中读取全部字节。
     *
     * @param is 输入流
     * @return 字节数组
     * @throws Exception 读取异常
     */
    private byte[] readBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return byte[].class
     */
    @Override
    public Class<byte[]> getType() {
        return byte[].class;
    }

}
