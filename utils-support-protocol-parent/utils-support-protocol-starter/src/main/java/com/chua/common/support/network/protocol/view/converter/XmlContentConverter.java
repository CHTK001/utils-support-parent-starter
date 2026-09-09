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
 * XML 内容转换器
 * <p>
 * 将其他格式转换为 XML 格式，包括：
 * 1. JSON -> XML
 * 2. 纯文本 -> XML
 * 3. 对象 -> XML
 *
 * @author CH
 * @since 2025-12-18
 */
@Slf4j
public class XmlContentConverter implements ContentConverter {

    private static final String[] SUPPORTED_MEDIA_TYPES = {
        MediaTypes.APPLICATION_XML,
        MediaTypes.TEXT_XML
    };

    @Override
    public byte[] convert(byte[] sourceData, String sourceMediaType, String targetMediaType, Charset charset)
            throws ConversionException {

        if (sourceData == null || sourceData.length == 0) {
            return new byte[0];
        }

        String targetType = MediaTypes.getMainType(targetMediaType);
        if (!MediaTypes.isXml(targetType)) {
            throw new ConversionException("此转换器仅支持转换为 XML 格式");
        }

        try {
            String sourceType = MediaTypes.getMainType(sourceMediaType);
            String content = new String(sourceData, charset);

            // 已经是 XML，直接返回
            if (MediaTypes.isXml(sourceType)) {
                return sourceData;
            }

            // JSON -> XML
            if (MediaTypes.isJson(sourceType)) {
                return convertJsonToXml(content, charset);
            }

            // 纯文本 -> XML
            if (MediaTypes.TEXT_PLAIN.equals(sourceType)) {
                return wrapTextInXml(content).getBytes(charset);
            }

            // HTML -> XML
            if (MediaTypes.TEXT_HTML.equals(sourceType)) {
                String text = extractTextFromHtml(content);
                return wrapTextInXml(text).getBytes(charset);
            }

            // 其他格式，包装为 XML
            return wrapTextInXml(content).getBytes(charset);

        } catch (Exception e) {
            throw new ConversionException("转换为 XML 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] convertFromObject(Object object, String mediaType, Charset charset) throws ConversionException {
        String targetType = MediaTypes.getMainType(mediaType);
        if (!MediaTypes.isXml(targetType)) {
            throw new ConversionException("此转换器仅支持转换为 XML 格式");
        }

        if (object == null) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><null/>".getBytes(charset);
        }

        try {
            String xml = convertObjectToXml(object);
            return xml.getBytes(charset);
        } catch (Exception e) {
            throw new ConversionException("对象转 XML 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T convertToObject(byte[] data, String mediaType, Class<T> targetType, Charset charset)
            throws ConversionException {
        // 此转换器不支持从 XML 转换为对象，请使用 JsonContentConverter
        throw new ConversionException("此转换器不支持转换为对象，请先转换为 JSON");
    }

    @Override
    public boolean canConvert(String sourceMediaType, String targetMediaType) {
        // 只支持转换为 XML
        return MediaTypes.isXml(MediaTypes.getMainType(targetMediaType));
    }

    @Override
    public boolean canConvertFromObject(Class<?> objectType, String mediaType) {
        return MediaTypes.isXml(MediaTypes.getMainType(mediaType));
    }

    @Override
    public boolean canConvertToObject(String mediaType, Class<?> objectType) {
        return false;
    }

    @Override
    public String[] getSupportedMediaTypes() {
        return SUPPORTED_MEDIA_TYPES.clone();
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public String getName() {
        return "XmlConverter";
    }

    /**
     * JSON 转 XML
     */
    private byte[] convertJsonToXml(String json, Charset charset) {
        try {
            Object obj = Json.fromJson(json, Object.class);
            return convertObjectToXml(obj).getBytes(charset);
        } catch (Exception e) {
            return wrapTextInXml(json).getBytes(charset);
        }
    }

    /**
     * 将对象转换为 XML
     */
    @SuppressWarnings("unchecked")
    private String convertObjectToXml(Object obj) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<root>\n");
        appendObjectToXml(obj, xml, "  ");
        xml.append("</root>");
        return xml.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendObjectToXml(Object obj, StringBuilder xml, String indent) {
        if (obj == null) {
            xml.append(indent).append("<null/>\n");
        } else if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = sanitizeXmlElementName(entry.getKey());
                xml.append(indent).append("<").append(key).append(">");
                if (isSimpleValue(entry.getValue())) {
                    xml.append(escapeXml(String.valueOf(entry.getValue())));
                } else {
                    xml.append("\n");
                    appendObjectToXml(entry.getValue(), xml, indent + "  ");
                    xml.append(indent);
                }
                xml.append("</").append(key).append(">\n");
            }
        } else if (obj instanceof Iterable) {
            for (Object item : (Iterable<?>) obj) {
                xml.append(indent).append("<item>");
                if (isSimpleValue(item)) {
                    xml.append(escapeXml(String.valueOf(item)));
                } else {
                    xml.append("\n");
                    appendObjectToXml(item, xml, indent + "  ");
                    xml.append(indent);
                }
                xml.append("</item>\n");
            }
        } else {
            xml.append(indent).append(escapeXml(String.valueOf(obj))).append("\n");
        }
    }

    private String wrapTextInXml(String text) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<data><![CDATA[" + 
               (text != null ? text : "") + "]]></data>";
    }

    private String extractTextFromHtml(String html) {
        String text = html.replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", "");
        text = text.replaceAll("(?i)<style[^>]*>[\\s\\S]*?</style>", "");
        text = text.replaceAll("<[^>]+>", "");
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                  .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    private boolean isSimpleValue(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private String sanitizeXmlElementName(String name) {
        if (name == null || name.isEmpty()) return "element";
        String sanitized = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (Character.isDigit(sanitized.charAt(0))) sanitized = "_" + sanitized;
        return sanitized;
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                  .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
