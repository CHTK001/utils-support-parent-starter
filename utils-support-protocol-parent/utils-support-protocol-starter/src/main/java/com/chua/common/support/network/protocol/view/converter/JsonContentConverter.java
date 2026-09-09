package com.chua.common.support.network.protocol.view.converter;

import com.chua.common.support.text.json.Json;
import com.chua.common.support.network.protocol.view.ContentConverter;
import com.chua.common.support.network.protocol.view.MediaTypes;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * JSON 内容转换器
 * <p>
 * 将其他格式转换为 JSON 格式，包括：
 * 1. 纯文本 -> JSON
 * 2. XML -> JSON
 * 3. 对象 -> JSON
 *
 * @author CH
 * @since 2025-07-23
 */
@Slf4j
public class JsonContentConverter implements ContentConverter {

    private static final String[] SUPPORTED_MEDIA_TYPES = {
        MediaTypes.APPLICATION_JSON
    };

    @Override
    public byte[] convert(byte[] sourceData, String sourceMediaType, String targetMediaType, Charset charset)
            throws ConversionException {

        if (sourceData == null || sourceData.length == 0) {
            return new byte[0];
        }

        String targetType = MediaTypes.getMainType(targetMediaType);
        if (!MediaTypes.isJson(targetType)) {
            throw new ConversionException("此转换器仅支持转换为 JSON 格式");
        }

        try {
            String sourceType = MediaTypes.getMainType(sourceMediaType);
            String content = new String(sourceData, charset);

            // 已经是 JSON，直接返回
            if (MediaTypes.isJson(sourceType)) {
                return sourceData;
            }

            // 纯文本 -> JSON
            if (MediaTypes.TEXT_PLAIN.equals(sourceType)) {
                return convertTextToJson(content, charset);
            }

            // XML -> JSON
            if (MediaTypes.isXml(sourceType)) {
                return convertXmlToJson(content, charset);
            }

            // HTML -> JSON
            if (MediaTypes.TEXT_HTML.equals(sourceType)) {
                return convertHtmlToJson(content, charset);
            }

            // 其他格式，尝试包装为 JSON 字符串
            return Json.toJson(content).getBytes(charset);

        } catch (Exception e) {
            throw new ConversionException("转换为 JSON 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] convertFromObject(Object object, String mediaType, Charset charset) throws ConversionException {
        String targetType = MediaTypes.getMainType(mediaType);
        if (!MediaTypes.isJson(targetType)) {
            throw new ConversionException("此转换器仅支持转换为 JSON 格式");
        }

        if (object == null) {
            return "null".getBytes(charset);
        }

        try {
            String json = Json.toJson(object);
            return json.getBytes(charset);
        } catch (Exception e) {
            throw new ConversionException("对象转 JSON 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T convertToObject(byte[] data, String mediaType, Class<T> targetType, Charset charset)
            throws ConversionException {

        if (data == null || data.length == 0) {
            return null;
        }

        String sourceType = MediaTypes.getMainType(mediaType);
        if (!MediaTypes.isJson(sourceType)) {
            throw new ConversionException("此转换器仅支持从 JSON 转换为对象");
        }

        try {
            String json = new String(data, charset);
            if (targetType == String.class) {
                return targetType.cast(json);
            }
            return Json.fromJson(json, targetType);
        } catch (Exception e) {
            throw new ConversionException("JSON 转对象失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean canConvert(String sourceMediaType, String targetMediaType) {
        // 只支持转换为 JSON
        return MediaTypes.isJson(MediaTypes.getMainType(sourceMediaType));
    }

    @Override
    public boolean canConvertFromObject(Class<?> objectType, String mediaType) {
        return MediaTypes.isJson(MediaTypes.getMainType(mediaType));
    }

    @Override
    public boolean canConvertToObject(String mediaType, Class<?> objectType) {
        return MediaTypes.isJson(MediaTypes.getMainType(mediaType));
    }

    @Override
    public String[] getSupportedMediaTypes() {
        return SUPPORTED_MEDIA_TYPES.clone();
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String getName() {
        return "JsonConverter";
    }

    /**
     * 纯文本转 JSON
     */
    private byte[] convertTextToJson(String text, Charset charset) {
        try {
            // 尝试解析为 JSON
            Json.fromJson(text, Object.class);
            return text.getBytes(charset);
        } catch (Exception e) {
            // 不是有效 JSON，包装为字符串
            return Json.toJson(text).getBytes(charset);
        }
    }

    /**
     * XML 转 JSON
     */
    private byte[] convertXmlToJson(String xml, Charset charset) {
        // 提取 CDATA 内容
        if (xml.contains("<![CDATA[") && xml.contains("]]>")) {
            int start = xml.indexOf("<![CDATA[") + 9;
            int end = xml.indexOf("]]>", start);
            if (end > start) {
                String content = xml.substring(start, end);
                try {
                    Json.fromJson(content, Object.class);
                    return content.getBytes(charset);
                } catch (Exception e) {
                    return Json.toJson(content).getBytes(charset);
                }
            }
        }
        
        // 提取 XML 内容
        String content = xml.replaceAll("<[^>]+>", "").trim();
        return Json.toJson(Map.of("content", content)).getBytes(charset);
    }

    /**
     * HTML 转 JSON
     */
    private byte[] convertHtmlToJson(String html, Charset charset) {
        // 移除脚本和样式标签
        String text = html.replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", "");
        text = text.replaceAll("(?i)<style[^>]*>[\\s\\S]*?</style>", "");
        // 移除所有 HTML 标签
        text = text.replaceAll("<[^>]+>", "");
        // 解码 HTML 实体
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                  .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        text = text.replaceAll("\\s+", " ").trim();
        
        return Json.toJson(Map.of("content", text)).getBytes(charset);
    }
}
