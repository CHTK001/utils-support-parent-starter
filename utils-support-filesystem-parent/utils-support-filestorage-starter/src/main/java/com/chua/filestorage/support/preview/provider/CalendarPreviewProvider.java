package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
* icalendar (ICS) 日历预览提供器。
* <p>SPI 类型：{@code preview-calendar}。解析 ICS 文件中的事件信息。</p>
*
* @author CH
* @since 4.0.0.42
* @param ics ics
* @return 解析ics的结果
* @param content 内容
* @param ext ext
* @param mime mime
 */
@Spi("preview-calendar")
public class CalendarPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("ics", "ical", "ifb", "icalendar"); // 支持exts

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        String ics = new String(content, StandardCharsets.UTF_8);
        List<CalendarEvent> events = parseIcs(ics);
        String html = buildHtml(events, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    /**
     * 解析Ics。
     *
     * @param ics 方法入参 ics
     * @return 结果列表，无数据时为空列表
     */
    private List<CalendarEvent> parseIcs(String ics) {
        List<CalendarEvent> events = new ArrayList<>();
        String[] lines = ics.split("\r?\n");

        CalendarEvent current = null;
        StringBuilder description = new StringBuilder();

        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) {
                current = new CalendarEvent();
                description = new StringBuilder();
                continue;
            }

            if (line.equals("END:VEVENT")) {
                if (current != null) {
                    current.description = description.toString().trim();
                    events.add(current);
                }
                current = null;
                continue;
            }

            if (current != null) {
                if (line.startsWith("SUMMARY:")) {
                    current.summary = line.substring(8);
                } else if (line.startsWith("DTSTART")) {
                    current.dtstart = extractDateTime(line);
                } else if (line.startsWith("DTEND")) {
                    current.dtend = extractDateTime(line);
                } else if (line.startsWith("LOCATION:")) {
                    current.location = line.substring(9);
                } else if (line.startsWith("DESCRIPTION:")) {
                    description.append(line.substring(12));
                } else if (line.startsWith("  ") || line.startsWith("\t")) {
                    // 续行
                    description.append(line.substring(1));
                }
            }
        }

        return events;
    }

    /**
    * extract日期时间。
    * @param line 线
    * @return extract日期时间的结果
    */
    private String extractDateTime(String line) {
        // 处理 DTSTART:20240101T120000Z 或 DTSTART;VALUE=DATE:20240101
    /**
    * calendar事件类。
    *
    * @author CH
    * @since 4.0.0
    * @param bytes bytes
    * @return human大小的结果
    */
        int colonIdx = line.indexOf(':');
        if (colonIdx < 0) {
            return line;
        }

        String value = line.substring(colonIdx + 1).trim();
        if (value.length() >= 8) {
            String year = value.substring(0, 4);
            String month = value.substring(4, 6);
            String day = value.substring(6, 8);
            StringBuilder sb = new StringBuilder(year).append("-").append(month).append("-").append(day);

            if (value.length() >= 15 && value.charAt(8) == 'T') {
                sb.append(" ").append(value.substring(9, 11))
                  .append(":").append(value.substring(11, 13))
                  .append(":").append(value.substring(13, 15));
            }

            return sb.toString();
        }
        return value;
    /**
    * 构建html。
    * @param events 事件
    * @param fileSize 文件大小
    * @return 构建html的结果
    */
    }

    /**
     * 构建Html。
     *
     * @param events 方法入参 events
     * @param fileSize 文件大小，不允许为 null
     * @return 结果字符串
     */
    private String buildHtml(List<CalendarEvent> events, long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".events{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:24px;max-width:700px;margin:0 auto}");
        sb.append(".event{padding:16px;margin-bottom:12px;background:#f9fafb;border-radius:8px;border-left:4px solid #3b82f6}");
        sb.append(".event:last-child{margin-bottom:0}");
        sb.append(".summary{font-weight:600;font-size:15px;margin-bottom:8px}");
        sb.append(".detail{color:#6b7280;font-size:13px;margin-bottom:4px}");
        sb.append(".detail span{color:#374151}");
        sb.append(".empty{color:#6b7280;text-align:center;padding:40px}");
        sb.append("</style></head><body>");

        sb.append("<div class=\"header\">");
        sb.append("<h1>日历事件预览</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append(" · 事件: ").append(events.size()).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"events\">");

        if (events.isEmpty()) {
            sb.append("<div class=\"empty\">未找到日历事件</div>");
        } else {
            for (CalendarEvent event : events) {
                sb.append("<div class=\"event\">");
                sb.append("<div class=\"summary\">").append(escapeHtml(event.summary != null ? event.summary : "无标题")).append("</div>");
                if (event.dtstart != null) {
                    sb.append("<div class=\"detail\">开始: <span>").append(escapeHtml(event.dtstart)).append("</span></div>");
                }
                if (event.dtend != null) {
                    sb.append("<div class=\"detail\">结束: <span>").append(escapeHtml(event.dtend)).append("</span></div>");
                }
                if (event.location != null && !event.location.isEmpty()) {
                    sb.append("<div class=\"detail\">地点: <span>").append(escapeHtml(event.location)).append("</span></div>");
                }
                if (event.description != null && !event.description.isEmpty()) {
                    sb.append("<div class=\"detail\">描述: <span>").append(escapeHtml(truncate(event.description, 200))).append("</span></div>");
                }
                sb.append("</div>");
            }
        }

        sb.append("</div></body></html>");
        return sb.toString();
    /**
    * truncate。
    * @param text 文本
    * @param maxLen 最大len
    * @return truncate的结果
    */
    }

    /**
     * truncate。
     *
     * @param text 文本，不允许为 null
     * @param maxLen 最大值Len，不允许为 null
     * @return 结果字符串
     */
    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    /**
    * escapehtml。
    * @param text 文本
    * @return escapeHtml的结果
    * @author CH
    * @since 4.0.0
    * @param bytes bytes
    */
    }

    /**
     * escapeHtml。
     *
     * @param text 文本，不允许为 null
     * @return 结果字符串
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * human大小。
     *
     * @param bytes 字节数组，不允许为 null
     * @return 结果字符串
     */
    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static class CalendarEvent {
        String summary; // summary
        String dtstart; // dtstart
        String dtend; // dtend
        String location; // 位置
        String description; // description
    }
}
