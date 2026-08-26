package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * XMind 思维导图预览提供器。
 * <p>SPI 类型：{@code preview-xmind}。解析 XMind ZIP 中的 content.json，
 * 生成树状 HTML 展示思维导图结构。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-xmind")
public class XMindPreviewProvider implements FileStoragePreviewProvider {

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && "xmind".equals(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        String json = extractContentJson(content);
        List<TopicNode> roots = parseTopics(json);
        String html = buildHtml(roots, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    /**
     * 从 XMind ZIP 中提取 content.json
     */
    private String extractContentJson(byte[] xmindBytes) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xmindBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("content.json".equals(entry.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        // 尝试备用路径
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xmindBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("content.json")) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "[]";
    }

    /**
     * 简易解析 XMind content.json 提取主题结构
     */
    private List<TopicNode> parseTopics(String json) {
        List<TopicNode> roots = new ArrayList<>();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return roots;
        }

        // 简易 JSON 解析（避免引入额外依赖）
        // XMind content.json 格式: [{"rootTopic":{"title":"...","children":{"attachments":[...]}}}]
        try {
            int rootIdx = json.indexOf("\"rootTopic\"");
            if (rootIdx >= 0) {
                TopicNode root = parseTopicObject(json, rootIdx);
                if (root != null) {
                    roots.add(root);
                }
            }
        } catch (Exception e) {
            // 解析失败时返回空列表
        }
        return roots;
    }

    /**
     * 递归解析主题对象
     */
    private TopicNode parseTopicObject(String json, int startPos) {
        // 找到 title
        String title = extractStringValue(json, startPos, "title");
        if (title == null) title = "Untitled";

        TopicNode node = new TopicNode(title);

        // 查找 children.attachments 或 children.attached
        int childrenIdx = json.indexOf("\"children\"", startPos);
        if (childrenIdx > startPos && childrenIdx - startPos < 500) {
            int attachmentsIdx = json.indexOf("\"attachments\"", childrenIdx);
            int attachedIdx = json.indexOf("\"attached\"", childrenIdx);

            int listStart = -1;
            if (attachmentsIdx > childrenIdx && (attachedIdx < 0 || attachmentsIdx < attachedIdx)) {
                listStart = attachmentsIdx + 13; // length of "\"attachments\":"
            } else if (attachedIdx > childrenIdx) {
                listStart = attachedIdx + 11; // length of "\"attached\":"
            }

            if (listStart > 0 && listStart < json.length()) {
                // 跳过空白和 [
                while (listStart < json.length() && Character.isWhitespace(json.charAt(listStart))) listStart++;
                if (listStart < json.length() && json.charAt(listStart) == '[') {
                    listStart++;
                    // 解析子主题数组
                    int depth = 0;
                    int itemStart = listStart;
                    for (int i = listStart; i < json.length(); i++) {
                        char c = json.charAt(i);
                        if (c == '{' && depth == 0) {
                            itemStart = i;
                            depth = 1;
                        } else if (c == '{') {
                            depth++;
                        } else if (c == '}') {
                            depth--;
                            if (depth == 0) {
                                TopicNode child = parseTopicObject(json, itemStart);
                                if (child != null) {
                                    node.children.add(child);
                                }
                            }
                        } else if (c == ']' && depth == 0) {
                            break;
                        }
                    }
                }
            }
        }
        return node;
    }

    /**
     * 提取 JSON 字符串值
     */
    private String extractStringValue(String json, int startPos, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search, startPos);
        if (idx < 0 || idx - startPos > 500) return null;

        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;

        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;

        int quoteEnd = findStringEnd(json, quoteStart + 1);
        if (quoteEnd < 0) return null;

        return json.substring(quoteStart + 1, quoteEnd)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n");
    }

    /**
     * 找到字符串结束位置（处理转义）
     */
    private int findStringEnd(String json, int start) {
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++; // 跳过转义字符
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 生成 HTML 页面
     */
    private String buildHtml(List<TopicNode> roots, long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".mindmap{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:24px}");
        sb.append(".node{margin:4px 0;padding-left:24px}");
        sb.append(".node-root{padding-left:0;margin-bottom:16px}");
        sb.append(".topic{display:inline-block;padding:8px 16px;border-radius:8px;font-weight:500;cursor:default}");
        sb.append(".topic-root{background:#3b82f6;color:#fff;font-size:16px}");
        sb.append(".topic-branch{background:#eff6ff;color:#1d4ed8;font-size:14px}");
        sb.append(".topic-leaf{background:#f9fafb;color:#374151;font-size:13px;border:1px solid #e5e7eb}");
        sb.append(".children{border-left:2px solid #d1d5db;margin-left:12px;padding-left:12px}");
        sb.append(".toggle{display:inline-block;width:16px;height:16px;line-height:16px;text-align:center;");
        sb.append("background:#e5e7eb;border-radius:4px;font-size:10px;cursor:pointer;margin-right:4px;color:#6b7280}");
        sb.append("</style></head><body>");

        // 头部信息
        sb.append("<div class=\"header\">");
        sb.append("<h1>XMind 思维导图预览</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append("</div>");
        sb.append("</div>");

        // 思维导图内容
        sb.append("<div class=\"mindmap\">");

        if (roots.isEmpty()) {
            sb.append("<p style=\"color:#6b7280\">无法解析思维导图内容</p>");
        } else {
            for (TopicNode root : roots) {
                renderNode(sb, root, 0, true);
            }
        }

        sb.append("</div></body></html>");
        return sb.toString();
    }

    /**
     * 递归渲染节点
     */
    private void renderNode(StringBuilder sb, TopicNode node, int depth, boolean isRoot) {
        String topicClass = isRoot ? "topic-root" : (depth <= 1 ? "topic-branch" : "topic-leaf");

        sb.append("<div class=\"node").append(isRoot ? " node-root" : "").append("\">");
        sb.append("<span class=\"topic ").append(topicClass).append("\">");

        if (!node.children.isEmpty() && !isRoot) {
            sb.append("<span class=\"toggle\">+</span>");
        }

        sb.append(escapeHtml(node.title));
        sb.append("</span>");

        if (!node.children.isEmpty()) {
            sb.append("<div class=\"children\">");
            for (TopicNode child : node.children) {
                renderNode(sb, child, depth + 1, false);
            }
            sb.append("</div>");
        }

        sb.append("</div>");
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * 主题节点
     */
    private static class TopicNode {
        String title;
        List<TopicNode> children = new ArrayList<>();

        TopicNode(String title) {
            this.title = title;
        }
    }
}
