package com.chua.common.support.network.protocol.view.converter;

import com.chua.common.support.network.protocol.view.ContentConverter;
import com.chua.common.support.network.protocol.view.MediaTypes;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 二进制内容转换器（默认转换器）
 * <p>
 * 当没有其他转换器可以处理时，使用此转换器直接返回原始数据。
 * 支持所有媒体类型，优先级最低。
 *
 * @author CH
 * @since 2025-12-18
 */
@Slf4j
public class BinaryContentConverter implements ContentConverter {

    private static final String[] SUPPORTED_MEDIA_TYPES = {
        MediaTypes.APPLICATION_OCTET_STREAM,
        MediaTypes.ALL
    };

    @Override
    public byte[] convert(byte[] sourceData, String sourceMediaType, String targetMediaType, Charset charset)
            throws ConversionException {
        // 直接返回原始数据，不做任何转换
        if (sourceData == null) {
            return new byte[0];
        }
        if (log.isDebugEnabled()) {
            log.debug("使用默认二进制转换器，直接返回原始数据");
        }
        return sourceData;
    }

    @Override
    public byte[] convertFromObject(Object object, String mediaType, Charset charset) throws ConversionException {
        if (object == null) {
            return new byte[0];
        }

        // 如果是字节数组，直接返回
        if (object instanceof byte[]) {
            return (byte[]) object;
        }

        // 如果是字符串，转换为字节
        if (object instanceof String) {
            return ((String) object).getBytes(charset);
        }

        // 其他类型，转换为字符串后再转字节
        return object.toString().getBytes(charset);
    }

    @Override
    public <T> T convertToObject(byte[] data, String mediaType, Class<T> targetType, Charset charset)
            throws ConversionException {
        if (data == null || data.length == 0) {
            return null;
        }

        // 如果目标类型是字节数组
        if (targetType == byte[].class) {
            return targetType.cast(data);
        }

        // 如果目标类型是字符串
        if (targetType == String.class) {
            return targetType.cast(new String(data, charset));
        }

        throw new ConversionException("二进制转换器仅支持转换为 byte[] 或 String 类型");
    }

    @Override
    public boolean canConvert(String sourceMediaType, String targetMediaType) {
        // 作为默认转换器，可以处理所有类型（但优先级最低）
        return true;
    }

    @Override
    public boolean canConvertFromObject(Class<?> objectType, String mediaType) {
        // 支持所有对象类型
        return true;
    }

    @Override
    public boolean canConvertToObject(String mediaType, Class<?> objectType) {
        // 仅支持转换为 byte[] 或 String
        return objectType == byte[].class || objectType == String.class;
    }

    @Override
    public String[] getSupportedMediaTypes() {
        return SUPPORTED_MEDIA_TYPES.clone();
    }

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE; // 最低优先级，作为默认处理器
    }

    @Override
    public String getName() {
        return "BinaryConverter";
    }
}
