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
 * HTML 内容转换器
 * <p>
 * 支持 HTML 格式与其他格式之间的转换，包括：
 * 1. HTML <-> 纯文本
 * 2. HTML <-> JSON
 * 3. 对象 <-> HTML
 *
 * @author CH
 * @since 2025-12-18
 */
@Slf4j
public class HtmlContentConverter implements ContentConverter {

    private static final String[] SUPPORTED_MEDIA_TYPES = {
        MediaTypes.TEXT_HTML,
        MediaTypes.TEXT_PLAIN,
        MediaTypes.APPLICATION_JSON
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

            // HTML -> 其他格式
            if (MediaTypes.TEXT_HTML.equals(sourceType)) {
                return convertFromHtml(sourceData, targetType, charset);
            }

            // 其他格式 -> HTML
            if (MediaTypes.TEXT_HTML.equals(targetType)) {
                return convertToHtml(sourceData, sourceType, charset);
            }

            throw new ConversionException("不支持的转换: " + sourceType + " -> " + targetType);

        } catch (Exception e) {
            throw new ConversionException("HTML 转换失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] convertFromObject(Object object, String mediaType, Charset charset) throws ConversionException {
        if (object == null) {
            return createEmptyHtml().getBytes(charset);
        }

        try {
            String targetType = MediaTypes.getMainType(mediaType);

            if (MediaTypes.TEXT_HTML.equals(targetType)) {
                // 对象转 HTML
                String html = convertObjectToHtml(object);
                return html.getBytes(charset);
            } else if (MediaTypes.isJson(targetType)) {
                // 对象转 JSON
                return Json.toJson(object).getBytes(charset);
            } else if (MediaTypes.TEXT_PLAIN.equals(targetType)) {
                // 对象转文本
                return object.toString().getBytes(charset);
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

            if (MediaTypes.TEXT_HTML.equals(sourceType)) {
                // HTML 转对象
                if (targetType == String.class) {
                    return targetType.cast(content);
                }
                // 提取文本内容
                String text = extractTextFromHtml(content);
                if (targetType == String.class) {
                    return targetType.cast(text);
                }
                // 尝试解析为 JSON
                return Json.fromJson(text, targetType);
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

        // 支持 HTML 与文本、JSON 之间的转换
        return (MediaTypes.TEXT_HTML.equals(sourceType) && 
                (MediaTypes.TEXT_PLAIN.equals(targetType) || MediaTypes.isJson(targetType))) ||
               (MediaTypes.TEXT_HTML.equals(targetType) && 
                (MediaTypes.TEXT_PLAIN.equals(sourceType) || MediaTypes.isJson(sourceType)));
    }

    @Override
    public boolean canConvertFromObject(Class<?> objectType, String mediaType) {
        String targetType = MediaTypes.getMainType(mediaType);
        return MediaTypes.TEXT_HTML.equals(targetType);
    }

    @Override
    public boolean canConvertToObject(String mediaType, Class<?> objectType) {
        String sourceType = MediaTypes.getMainType(mediaType);
        return MediaTypes.TEXT_HTML.equals(sourceType);
    }

    @Override
    public String[] getSupportedMediaTypes() {
        return SUPPORTED_MEDIA_TYPES.clone();
    }

    @Override
    public int getPriority() {
        return 40;
    }

    /**
     * 从 HTML 转换为其他格式
     */
    private byte[] convertFromHtml(byte[] htmlData, String targetType, Charset charset) throws ConversionException {
        String html = new String(htmlData, charset);

        if (MediaTypes.TEXT_PLAIN.equals(targetType)) {
            // HTML -> 纯文本
            String text = extractTextFromHtml(html);
            return text.getBytes(charset);
        } else if (MediaTypes.isJson(targetType)) {
            // HTML -> JSON
            String text = extractTextFromHtml(html);
            return Json.toJson(Map.of("content", text, "html", html)).getBytes(charset);
        }

        throw new ConversionException("不支持的 HTML 转换目标类型: " + targetType);
    }

    /**
     * 从其他格式转换为 HTML
     */
    private byte[] convertToHtml(byte[] sourceData, String sourceType, Charset charset) throws ConversionException {
        String content = new String(sourceData, charset);

        if (MediaTypes.TEXT_PLAIN.equals(sourceType)) {
            // 文本 -> HTML
            String html = convertTextToHtml(content, "Text Content");
            return html.getBytes(charset);
        } else if (MediaTypes.isJson(sourceType)) {
            // JSON -> HTML
            String html = convertJsonToHtml(content);
            return html.getBytes(charset);
        }

        throw new ConversionException("不支持的 HTML 转换源类型: " + sourceType);
    }

    /**
     * 将对象转换为 HTML
     */
    @SuppressWarnings("unchecked")
    private String convertObjectToHtml(Object obj) {
        StringBuilder html = new StringBuilder();
        html.append(createHtmlHeader("Data View"));
        html.append("<body>\n");
        html.append("<div class=\"container\">\n");
        
        if (obj instanceof Map) {
            html.append(convertMapToHtmlTable((Map<String, Object>) obj));
        } else if (obj instanceof Iterable) {
            html.append(convertIterableToHtmlList((Iterable<?>) obj));
        } else {
            html.append("<pre>").append(escapeHtml(obj.toString())).append("</pre>\n");
        }
        
        html.append("</div>\n");
        html.append("</body>\n</html>");
        return html.toString();
    }

    /**
     * 将 Map 转换为 HTML 表格
     */
    @SuppressWarnings("unchecked")
    private String convertMapToHtmlTable(Map<String, Object> map) {
        StringBuilder table = new StringBuilder();
        table.append("<table class=\"data-table\">\n");
        table.append("<thead><tr><th>Key</th><th>Value</th></tr></thead>\n");
        table.append("<tbody>\n");
        
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            table.append("<tr>\n");
            table.append("  <td>").append(escapeHtml(entry.getKey())).append("</td>\n");
            table.append("  <td>");
            Object value = entry.getValue();
            if (value instanceof Map) {
                table.append(convertMapToHtmlTable((Map<String, Object>) value));
            } else if (value instanceof Iterable) {
                table.append(convertIterableToHtmlList((Iterable<?>) value));
            } else {
                table.append(escapeHtml(String.valueOf(value)));
            }
            table.append("</td>\n");
            table.append("</tr>\n");
        }
        
        table.append("</tbody>\n");
        table.append("</table>\n");
        return table.toString();
    }

    /**
     * 将 Iterable 转换为 HTML 列表
     */
    @SuppressWarnings("unchecked")
    private String convertIterableToHtmlList(Iterable<?> items) {
        StringBuilder list = new StringBuilder();
        list.append("<ul class=\"data-list\">\n");
        
        for (Object item : items) {
            list.append("<li>");
            if (item instanceof Map) {
                list.append(convertMapToHtmlTable((Map<String, Object>) item));
            } else if (item instanceof Iterable) {
                list.append(convertIterableToHtmlList((Iterable<?>) item));
            } else {
                list.append(escapeHtml(String.valueOf(item)));
            }
            list.append("</li>\n");
        }
        
        list.append("</ul>\n");
        return list.toString();
    }

    /**
     * 将文本转换为 HTML
     */
    private String convertTextToHtml(String text, String title) {
        StringBuilder html = new StringBuilder();
        html.append(createHtmlHeader(title));
        html.append("<body>\n");
        html.append("<div class=\"container\">\n");
        html.append("<pre>").append(escapeHtml(text)).append("</pre>\n");
        html.append("</div>\n");
        html.append("</body>\n</html>");
        return html.toString();
    }

    /**
     * 将 JSON 转换为 HTML
     */
    @SuppressWarnings("unchecked")
    private String convertJsonToHtml(String json) {
        try {
            Object obj = Json.fromJson(json, Object.class);
            return convertObjectToHtml(obj);
        } catch (Exception e) {
            // 如果解析失败，将 JSON 作为文本显示
            return convertTextToHtml(json, "JSON Data");
        }
    }

    /**
     * 从 HTML 中提取文本内容
     */
    private String extractTextFromHtml(String html) {
        if (html == null) {
            return "";
        }
        // 移除脚本和样式标签
        String text = html.replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", "");
        text = text.replaceAll("(?i)<style[^>]*>[\\s\\S]*?</style>", "");
        // 移除所有 HTML 标签
        text = text.replaceAll("<[^>]+>", "");
        // 解码 HTML 实体
        text = text.replace("&amp;", "&")
                  .replace("&lt;", "<")
                  .replace("&gt;", ">")
                  .replace("&quot;", "\"")
                  .replace("&#39;", "'")
                  .replace("&nbsp;", " ");
        // 压缩空白字符
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    /**
     * 创建空的 HTML
     */
    private String createEmptyHtml() {
        return createHtmlHeader("Empty") + "<body></body></html>";
    }

    /**
     * 创建 HTML 头部
     */
    private String createHtmlHeader(String title) {
        return "<!DOCTYPE html>\n" +
               "<html lang=\"zh-CN\">\n" +
               "<head>\n" +
               "  <meta charset=\"UTF-8\">\n" +
               "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
               "  <title>" + escapeHtml(title) + "</title>\n" +
               "  <style>\n" +
               "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }\n" +
               "    .container { max-width: 1200px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
               "    .data-table { width: 100%; border-collapse: collapse; margin: 10px 0; }\n" +
               "    .data-table th, .data-table td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n" +
               "    .data-table th { background: #f8f9fa; font-weight: 600; }\n" +
               "    .data-table tr:nth-child(even) { background: #f9f9f9; }\n" +
               "    .data-list { list-style-type: disc; padding-left: 20px; }\n" +
               "    .data-list li { margin: 5px 0; }\n" +
               "    pre { background: #f8f9fa; padding: 15px; border-radius: 4px; overflow-x: auto; white-space: pre-wrap; word-wrap: break-word; }\n" +
               "  </style>\n" +
               "</head>\n";
    }

    /**
     * 转义 HTML 特殊字符
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
    }
}
