package com.chua.common.support.network.protocol.view.converter;

import com.chua.common.support.text.json.Json;
import com.chua.common.support.network.protocol.view.ContentConverter;
import com.chua.common.support.network.protocol.view.MediaTypes;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 纯文本内容转换器
 * <p>
 * 支持纯文本格式与其他格式之间的转换，包括：
 * 1. 文本 <-> JSON
 * 2. 文本 <-> XML
 * 3. 对象 <-> 文本
 *
 * @author CH
 * @since 2025-07-23
 */
@Slf4j
public class TextContentConverter implements ContentConverter {

    private static final String[] SUPPORTED_MEDIA_TYPES = {
        MediaTypes.TEXT_PLAIN
    };

    @Override
    public byte[] convert(byte[] sourceData, String sourceMediaType, String targetMediaType, Charset charset)
            throws ConversionException {

        if (sourceData == null || sourceData.length == 0) {
            return new byte[0];
        }

        try {
            String sourceType = MediaTypes.getMainType(sourceMediaType);
            String targetType = MediaTypes.getMainType(targetMediaType);
            String content = new String(sourceData, charset);

            // 文本 -> 其他格式
            if (MediaTypes.TEXT_PLAIN.equals(sourceType)) {
                return convertFromText(content, targetType, charset);
            }

            // 其他格式 -> 文本
            if (MediaTypes.TEXT_PLAIN.equals(targetType)) {
                return convertToText(content, sourceType, charset);
            }

            throw new ConversionException("不支持的转换: " + sourceType + " -> " + targetType);

        } catch (Exception e) {
            throw new ConversionException("文本转换失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] convertFromObject(Object object, String mediaType, Charset charset) throws ConversionException {
        if (object == null) {
            return "".getBytes(charset);
        }

        try {
            String targetType = MediaTypes.getMainType(mediaType);

            if (MediaTypes.TEXT_PLAIN.equals(targetType)) {
                // 对象转纯文本
                return convertObjectToText(object).getBytes(charset);
            }

            throw new ConversionException("不支持的对象转换目标类型: " + targetType);

        } catch (Exception e) {
            throw new ConversionException("对象转换失败: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T convertToObject(byte[] data, String mediaType, Class<T> targetType, Charset charset)
            throws ConversionException {

        if (data == null || data.length == 0) {
            return null;
        }

        try {
            String sourceType = MediaTypes.getMainType(mediaType);
            String content = new String(data, charset);

            if (targetType == String.class) {
                // 任何格式都可以转为字符串
                return targetType.cast(content);
            }

            if (MediaTypes.TEXT_PLAIN.equals(sourceType)) {
                // 文本转对象
                return convertTextToObject(content, targetType);
            }

            throw new ConversionException("不支持的对象转换源类型: " + sourceType);

        } catch (Exception e) {
            throw new ConversionException("数据转对象失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean canConvert(String sourceMediaType, String targetMediaType) {
        String sourceType = MediaTypes.getMainType(sourceMediaType);
        String targetType = MediaTypes.getMainType(targetMediaType);

        // 支持纯文本与 JSON、XML 之间的转换
        return (MediaTypes.TEXT_PLAIN.equals(sourceType) && 
                (MediaTypes.isJson(targetType) || MediaTypes.isXml(targetType))) ||
               (MediaTypes.TEXT_PLAIN.equals(targetType) && 
                (MediaTypes.isJson(sourceType) || MediaTypes.isXml(sourceType)));
    }

    @Override
    public boolean canConvertFromObject(Class<?> objectType, String mediaType) {
        String targetType = MediaTypes.getMainType(mediaType);
        return MediaTypes.TEXT_PLAIN.equals(targetType);
    }

    @Override
    public boolean canConvertToObject(String mediaType, Class<?> objectType) {
        String sourceType = MediaTypes.getMainType(mediaType);
        return MediaTypes.TEXT_PLAIN.equals(sourceType);
    }

    @Override
    public String[] getSupportedMediaTypes() {
        return SUPPORTED_MEDIA_TYPES.clone();
    }

    @Override
    public int getPriority() {
        return 30; // 中等优先级
    }

    @Override
    public String getName() {
        return "TextConverter";
    }

    /**
     * 从纯文本转换为其他格式
     */
    private byte[] convertFromText(String text, String targetType, Charset charset) throws ConversionException {
        if (MediaTypes.isJson(targetType)) {
            // 文本 -> JSON
            String json = Json.toJson(text);
            return json.getBytes(charset);
        } else if (MediaTypes.isXml(targetType)) {
            // 文本 -> XML
            String xml = convertTextToXml(text);
            return xml.getBytes(charset);
        }

        throw new ConversionException("不支持的文本转换目标类型: " + targetType);
    }

    /**
     * 从其他格式转换为纯文本
     */
    private byte[] convertToText(String content, String sourceType, Charset charset) throws ConversionException {
        if (MediaTypes.isJson(sourceType)) {
            // JSON -> 文本
            String text = convertJsonToText(content);
            return text.getBytes(charset);
        } else if (MediaTypes.isXml(sourceType)) {
            // XML -> 文本
            String text = convertXmlToText(content);
            return text.getBytes(charset);
        }

        throw new ConversionException("不支持的文本转换源类型: " + sourceType);
    }

    /**
     * 将对象转换为文本
     */
    private String convertObjectToText(Object object) {
        if (object == null) {
            return "";
        }
        if (object instanceof String) {
            return (String) object;
        }
        return object.toString();
    }

    /**
     * 将文本转换为对象
     */
    @SuppressWarnings("unchecked")
    private <T> T convertTextToObject(String text, Class<T> targetType) throws ConversionException {
        if (targetType == String.class) {
            return (T) text;
        }

        // 尝试将文本解析为 JSON 对象
        try {
            return Json.fromJson(text, targetType);
        } catch (Exception e) {
            throw new ConversionException("无法将文本转换为对象: " + targetType.getName(), e);
        }
    }

    /**
     * 将文本转换为 XML
     */
    private String convertTextToXml(String text) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<text><![CDATA[" +
               (text != null ? text : "") + "]]></text>";
    }

    /**
     * 将 JSON 转换为文本
     */
    private String convertJsonToText(String json) {
        try {
            // 尝试格式化 JSON
            Object obj = Json.fromJson(json, Object.class);
            return Json.toPrettyJson(obj);
        } catch (Exception e) {
            // 如果解析失败，返回原始 JSON
            return json;
        }
    }

    /**
     * 将 XML 转换为文本
     */
    private String convertXmlToText(String xml) {
        if (xml == null) {
            return "";
        }

        // 提取 CDATA 内容
        if (xml.contains("<![CDATA[") && xml.contains("]]>")) {
            int start = xml.indexOf("<![CDATA[") + 9;
            int end = xml.indexOf("]]>", start);
            if (end > start) {
                return xml.substring(start, end);
            }
        }

        // 简单的 XML 标签移除
        return xml.replaceAll("<[^>]+>", "").trim();
    }
}
