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
 * xmind 思维导图预览提供器。
 *
 * <p>SPI 类型：{@code preview-xmind}。解析 XMind ZIP 包中的内容，生成可折叠树状 HTML 展示思维导图结构。
 * 支持两种格式：</p>
 * <ul>
 *   <li><strong>XMind Zen/2020+</strong>：ZIP 内 {@code content.json}，JSON 格式</li>
 *   <li><strong>XMind 8</strong>：ZIP 内 {@code content.xml}，XML 格式</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-xmind")
public class XMindPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 判断是否支持该扩展名的预览。
     *
     * @param ext  文件扩展名（不含点号，如 "xmind"），可为 空
     * @param mime MIME 类型（本实现不依赖 MIME，仅校验扩展名）
     * @return 当 {@code ext} 非 空 且等于 "xmind"（不区分大小写）时返回 true
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && "xmind".equals(ext.toLowerCase(Locale.ENGLISH));
    }

    /**
     * 预览 xmind 思维导图，返回包含可折叠树结构的完整 HTML 页面。
     *
     * <p>解析流程：优先读取 content.json（新格式），失败后读取 content.xml（旧格式），
     * 均失败时返回"无法解析"提示页面。</p>
     *
     * @param content xmind 文件的原始字节内容（压缩 格式）
     * @param ext     文件扩展名（如 "xmind"）
     * @param mime    MIME 类型（本实现忽略）
     * @return 预览结果，包含 HTML 内容
     * @throws IOException 读取 压缩 时发生 I/O 错误
     */
    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        List<TopicNode> roots = extractFromZip(content);
        String html = buildHtml(roots, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    /**
     * 从 xmind 压缩 包中提取主题层级结构。
     *
     * <p>依次尝试：</p>
     * <ol>
     *   <li>{@code content.json}（XMind Zen/2020+ 格式）</li>
     *   <li>{@code content.xml}（XMind 8 格式）</li>
     * </ol>
     * <p>首次成功解析到非空主题列表即返回，两者均失败时返回空列表。</p>
     *
     * @param xmindBytes xmind 文件的原始 压缩 字节内容
     * @return 根主题列表（通常只有一个根主题），解析失败时返回空列表
     * @throws IOException 读取 压缩 流时发生 I/O 错误
     */
    private List<TopicNode> extractFromZip(byte[] xmindBytes) throws IOException {
 // 优先尝试 内容.json（xmind Zen/2020+ 新格式）
        String json = extractFromZipByName(xmindBytes, "content.json");
        if (json != null) {
            List<TopicNode> roots = parseTopics(json);
            if (!roots.isEmpty()) {
                return roots;
            }
        }

 // 回退尝试 内容.xml（xmind 8 旧格式）
        String xml = extractFromZipByName(xmindBytes, "content.xml");
        if (xml != null) {
            List<TopicNode> roots = parseTopicsFromXml(xml);
            if (!roots.isEmpty()) {
                return roots;
            }
        }

        return List.of();
    }

    /**
     * 按文件名从 压缩 中提取单个文件的内容。
     *
     * <p>先做精确匹配（根目录下同名文件），未命中则做后缀模糊匹配
     * （兼容 {@code xmind/content.json} 等带路径前缀的情况）。</p>
     *
     * @param xmindBytes 压缩 文件的原始字节内容
     * @param targetName 目标文件名（如 "内容.json"、"内容.xml"）
     * @return 文件内容字符串；ZIP 中不存在该文件时返回 空
     * @throws IOException 读取 压缩 流时发生 I/O 错误
     */
    private String extractFromZipByName(byte[] xmindBytes, String targetName) throws IOException {
        // 第一趟：精确匹配文件名
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xmindBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (targetName.equals(entry.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
 // 第二趟：后缀模糊匹配（兼容含路径前缀的条目，如 xmind/内容.json）
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xmindBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(targetName)) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /**
     * 解析 xmind Zen/2020+ 的 内容.json，提取主题层级结构。
     *
     * <p>content.json 格式示例：</p>
     * <pre>[{"rootTopic":{"title":"根主题","children":{"attached":[...]}}}]</pre>
     * <p>本方法采用简易 JSON 字符串扫描（不依赖 JSON 库），从 {@code "rootTopic"} 开始递归解析。</p>
     *
     * @param json 内容.json 的文件内容字符串
     * @return 根主题列表（通常只有一个元素），解析失败或内容为空时返回空列表
     */
    private List<TopicNode> parseTopics(String json) {
        List<TopicNode> roots = new ArrayList<>();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return roots;
        }

        try {
            int rootIdx = json.indexOf("\"rootTopic\"");
            if (rootIdx >= 0) {
                TopicNode root = parseTopicObject(json, rootIdx);
                if (root != null) {
                    roots.add(root);
                }
            }
        } catch (Exception e) {
            // JSON 解析异常时静默返回已解析内容（或空列表）
        }
        return roots;
    }

    /**
     * 解析 xmind 8 的 内容.xml，提取主题层级结构。
     *
     * <p>XML 命名空间：{@code urn:xmind:xmap:xmlns:content:2.0}，层级关系：</p>
     * <pre>xmap-content → sheet → topic → title + children → topics(type="attached") → topic …</pre>
     * <p>使用 JDK 内置 DOM 解析器，禁用外部 DTD/实体加载以防止 XXE 攻击。</p>
     *
     * @param xml 内容.xml 的文件内容字符串
     * @return 根主题列表（每个 sheet 的根主题），解析失败或内容为空时返回空列表
     */
    private List<TopicNode> parseTopicsFromXml(String xml) {
        List<TopicNode> roots = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return roots;
        }
        try {
 // 创建安全的 文档构建器（禁用外部实体，防止 XXE）
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();

            org.w3c.dom.Document doc = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            // 获取所有 sheet 元素（兼容带命名空间和不带命名空间两种情况）
            org.w3c.dom.NodeList sheets = doc.getElementsByTagNameNS(
                    "urn:xmind:xmap:xmlns:content:2.0", "sheet");
            if (sheets.getLength() == 0) {
                sheets = doc.getElementsByTagName("sheet");
            }

            for (int i = 0; i < sheets.getLength(); i++) {
                org.w3c.dom.Element sheet = (org.w3c.dom.Element) sheets.item(i);
                org.w3c.dom.NodeList topicList = sheet.getElementsByTagNameNS(
                        "urn:xmind:xmap:xmlns:content:2.0", "topic");
                if (topicList.getLength() == 0) {
                    topicList = sheet.getElementsByTagName("topic");
                }
                for (int j = 0; j < topicList.getLength(); j++) {
                    org.w3c.dom.Element topic = (org.w3c.dom.Element) topicList.item(j);
                    String parentId = topic.getAttribute("id");
                    // 只处理顶级 topic（其父节点不是 topic 元素）
                    if (parentId != null && topic.getParentNode() != null
                            && !"topic".equals(topic.getParentNode().getLocalName())) {
                        TopicNode node = buildNodeFromXml(topic);
                        if (node != null) {
                            roots.add(node);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // XML 解析异常时静默返回已解析内容（或空列表）
        }
        return roots;
    }

    /**
     * 从 XML 的 topic 元素递归构建 {@link TopicNode} 树。
     *
     * <p>遍历路径：{@code topic → title}（取标题）+ {@code topic → children → topics → topic}
     * （取子主题列表，递归构建）。</p>
     *
     * @param topicEl XML 中的 topic DOM 元素
     * @return 构建好的主题节点（title 非空，children 可能为空）
     */
    private TopicNode buildNodeFromXml(org.w3c.dom.Element topicEl) {
        String title = getTextContent(topicEl, "title");
        if (title == null || title.isBlank()) {
            title = "Untitled";
        }
        TopicNode node = new TopicNode(title);

        // 查找 children/topics[@type="attached"]/topic（子主题列表）
        org.w3c.dom.NodeList childrenList = topicEl.getElementsByTagNameNS(
                "urn:xmind:xmap:xmlns:content:2.0", "children");
        if (childrenList.getLength() == 0) {
            childrenList = topicEl.getElementsByTagName("children");
        }
        if (childrenList.getLength() > 0) {
            org.w3c.dom.Element childrenEl = (org.w3c.dom.Element) childrenList.item(0);
            org.w3c.dom.NodeList topicsList = childrenEl.getElementsByTagNameNS(
                    "urn:xmind:xmap:xmlns:content:2.0", "topics");
            if (topicsList.getLength() == 0) {
                topicsList = childrenEl.getElementsByTagName("topics");
            }
            if (topicsList.getLength() > 0) {
                org.w3c.dom.Element topicsEl = (org.w3c.dom.Element) topicsList.item(0);
                org.w3c.dom.NodeList subTopics = topicsEl.getElementsByTagNameNS(
                        "urn:xmind:xmap:xmlns:content:2.0", "topic");
                if (subTopics.getLength() == 0) {
                    subTopics = topicsEl.getElementsByTagName("topic");
                }
                for (int i = 0; i < subTopics.getLength(); i++) {
                    org.w3c.dom.Element child = (org.w3c.dom.Element) subTopics.item(i);
                    TopicNode childNode = buildNodeFromXml(child);
                    if (childNode != null) {
                        node.children.add(childNode);
                    }
                }
            }
        }
        return node;
    }

    /**
     * 从 DOM Element 中获取指定子元素的文本内容。
     *
     * <p>优先按命名空间查找，未命中时回退为无命名空间查找。</p>
     *
     * @param parent   父 DOM 元素
     * @param childName 子元素名称（如 "title"）
     * @return 子元素的 修剪 后文本内容；不存在或为空时返回 空
     */
    private String getTextContent(org.w3c.dom.Element parent, String childName) {
        org.w3c.dom.NodeList list = parent.getElementsByTagNameNS(
                "urn:xmind:xmap:xmlns:content:2.0", childName);
        if (list.getLength() == 0) {
            list = parent.getElementsByTagName(childName);
        }
        if (list.getLength() > 0) {
            String text = list.item(0).getTextContent();
            return text != null ? text.trim() : null;
        }
        return null;
    }

    /**
     * 递归解析 JSON 格式的 topic 对象（用于 xmind Zen/2020+ 内容.json）。
     *
     * <p>从 {@code startPos} 位置开始，先提取 {@code title} 字段，
     * 再定位 {@code children → attachments/attached} 数组，
     * 通过大括号计数递归解析每个子 topic 对象。</p>
     *
     * @param json     内容.json 的完整内容字符串
     * @param startPos 当前 topic 对象在 JSON 字符串中的起始位置
     * @return 解析出的主题节点；解析失败时返回 空
     */
    private TopicNode parseTopicObject(String json, int startPos) {
        String title = extractStringValue(json, startPos, "title");
        if (title == null) {
            title = "Untitled";
        }

        TopicNode node = new TopicNode(title);

        // 定位 children 对象中的 attachments 或 attached 数组（子主题列表）
        int childrenIdx = json.indexOf("\"children\"", startPos);
        if (childrenIdx > startPos && childrenIdx - startPos < 500) {
            int attachmentsIdx = json.indexOf("\"attachments\"", childrenIdx);
            int attachedIdx = json.indexOf("\"attached\"", childrenIdx);

            int listStart = -1;
            if (attachmentsIdx > childrenIdx && (attachedIdx < 0 || attachmentsIdx < attachedIdx)) {
                listStart = attachmentsIdx + 13; // "\"attachments\":".length()
            } else if (attachedIdx > childrenIdx) {
                listStart = attachedIdx + 11; // "\"attached\":".length()
            }

            if (listStart > 0 && listStart < json.length()) {
                // 跳过空白字符和左方括号 '['
                while (listStart < json.length() && Character.isWhitespace(json.charAt(listStart))) {
                    listStart++;
                }
                if (listStart < json.length() && json.charAt(listStart) == '[') {
                    listStart++;
                    // 通过大括号深度计数解析每个子 topic 对象
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
     * 从 JSON 字符串中提取指定 键 对应的字符串值。
     *
     * <p>从 {@code startPos} 开始查找 {@code "key": "value"} 结构，
     * 处理转义字符（反斜杠、双引号、换行）。</p>
     *
     * @param json     完整的 JSON 字符串
     * @param startPos 起始搜索位置
     * @param key      要查找的字段名（不含引号，如 "title"）
     * @return 字段值字符串；未找到或距离超过 500 字符时返回 空
     */
    private String extractStringValue(String json, int startPos, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search, startPos);
        if (idx < 0 || idx - startPos > 500) {
            return null;
        }

        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) {
            return null;
        }

        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) {
            return null;
        }

        int quoteEnd = findStringEnd(json, quoteStart + 1);
        if (quoteEnd < 0) {
            return null;
        }

        return json.substring(quoteStart + 1, quoteEnd)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n");
    }

    /**
     * 在 JSON 字符串中查找未转义的双引号结束位置。
     *
     * <p>从 {@code start} 位置开始向后扫描，遇到反斜杠则跳过下一个字符（转义处理），
     * 遇到未转义的双引号则返回其位置。</p>
     *
     * @param json  完整的 JSON 字符串
     * @param start 字符串内容的起始位置（紧跟第一个双引号之后）
     * @return 字符串结束位置（双引号的索引）；未找到时返回 -1
     */
    private int findStringEnd(String json, int start) {
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++; // 跳过转义字符及其后一个字符
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 将主题根列表构建为完整的 HTML 预览页面。
     *
     * <p>页面包含：头部信息（标题 + 文件大小）+ 可折叠思维导图树区域。
     * 无主题时显示"无法解析思维导图内容"提示。</p>
     *
     * @param roots    根主题列表（通常只有一个根主题）
     * @param fileSize 原始 xmind 文件的字节大小（用于显示文件信息）
     * @return 完整的 HTML 页面字符串
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

        // 思维导图内容区域
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
     * 递归渲染单个主题节点为 HTML 片段。
     *
     * <p>根据层级深度应用不同样式：根节点蓝色背景、一级分支浅蓝、其余灰白边框。
     * 非根且有子节点时显示 "+" 折叠按钮。</p>
     *
     * @param sb     HTML 输出缓冲区
     * @param node   当前主题节点
     * @param depth  当前层级深度（根节点为 0）
     * @param isRoot 是否为根节点
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

    /**
     * 转义 HTML 特殊字符，防止 XSS 注入。
     *
     * @param text 原始文本（可为 空）
     * @return 转义后的安全文本；输入为 空 时返回空字符串
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 将字节数格式化为人类可读的文件大小。
     *
     * @param bytes 文件大小（字节）
     * @return 格式化后的字符串，如 "1.5 KB"、"2.3 MB"
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

    /**
     * 思维导图主题节点（内部数据结构）。
     *
     * <p>每个节点包含一个标题和零到多个子节点，构成树形结构。</p>
     * @author CH
     * @since 4.0.0
     */
    private static class TopicNode {
        /**
         * 主题标题（不可为 空）
        */
        String title;
        /**
         * 子主题列表（可能为空）
        */
        List<TopicNode> children = new ArrayList<>();

        /**
         * 创建主题节点。
         *
         * @param title 主题标题
         */
        TopicNode(String title) {
            this.title = title;
        }
    }
}
