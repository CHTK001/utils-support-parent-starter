package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
     * JSON 解析器（线程安全，复用同一实例）。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
     * 解析 xmind Zen/2020+ 的 content.json，提取主题层级结构。
     *
     * <p>content.json 格式示例：</p>
     * <pre>[{"rootTopic":{"title":"根主题","children":{"attached":[...]}}}]</pre>
     *
     * <p><b>为什么改用 Jackson 而不是字符串扫描</b>：原实现用
     * {@code json.indexOf("\"children\"", startPos)} 找子节点，**搜索范围没有限定在当前对象内**
     * —— 一个没有 children 的节点会抓到**兄弟或后续节点**的 children，递归下去把主题树
     * 解析成一棵重复十几遍的错树（实测用合法 XMind 样本时，同一段叶子主题重复出现十余次）。
     * 这类「按索引扫 JSON」的写法对嵌套与转义都不健壮，改用同包 {@code JupyterPreviewProvider}
     * 已经在用的 Jackson。</p>
     *
     * @param json 内容.json 的文件内容字符串
     * @return 根主题列表（通常只有一个元素），解析失败或内容为空时返回空列表
     */
    private List<TopicNode> parseTopics(String json) {
        List<TopicNode> roots = Wraps.list();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return roots;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (root == null) {
                return roots;
            }
            if (root.isArray()) {
                // 顶层是数组：每个元素是一张画布（sheet），取它的 rootTopic
                for (JsonNode sheet : root) {
                    JsonNode rootTopic = sheet.path("rootTopic");
                    if (!rootTopic.isMissingNode() && !rootTopic.isNull()) {
                        roots.add(buildNodeFromJson(rootTopic));
                    }
                }
            } else if (root.isObject()) {
                JsonNode rootTopic = root.path("rootTopic");
                if (!rootTopic.isMissingNode() && !rootTopic.isNull()) {
                    roots.add(buildNodeFromJson(rootTopic));
                }
            }
        } catch (Exception e) {
            // JSON 非法时静默返回已解析内容（或空列表），由上层回落「无法解析」提示
        }
        return roots;
    }

    /**
     * 从 JSON 的 topic 对象递归构建主题节点树。
     *
     * <p>子主题位置为 {@code children.attached}（XMind Zen/2020+），
     * 兼容 {@code children.attachments} 这一拼写。</p>
     *
     * @param topic JSON 中的 topic 对象
     * @return 构建好的主题节点（标题为空时置为 {@code Untitled}）
     */
    private TopicNode buildNodeFromJson(JsonNode topic) {
        String title = topic.path("title").asText("");
        if (title.isBlank()) {
            title = "Untitled";
        }
        TopicNode node = new TopicNode(title);
        JsonNode children = topic.path("children");
        // attached=子主题、detached=浮动主题，都算子节点（与前端渲染器口径一致）。
        // 兼容历史拼写 attachments。
        appendJsonChildren(node, children.path("attached"));
        appendJsonChildren(node, children.path("detached"));
        appendJsonChildren(node, children.path("attachments"));
        return node;
    }

    /**
     * 把 JSON 数组里的子主题挂到 {@code node} 下（非数组时忽略）。
     *
     * @param node  父节点
     * @param array 子主题 JSON 数组（attached / detached / attachments）
     */
    private void appendJsonChildren(TopicNode node, JsonNode array) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode child : array) {
            node.children.add(buildNodeFromJson(child));
        }
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
        List<TopicNode> roots = Wraps.list();
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

            // 根主题 = 每个 sheet 的**直接** topic 子元素。
            //
            // 必须用直接子元素而不是 getElementsByTagNameNS：后者是**递归**的，会把
            // 子主题（其父节点是 <topics> 而非 <topic>）也当成根，于是同一棵子树被反复
            // 输出（实测一份真实 XMind 8 文件 2 个 sheet / 46 个主题被解析成 46 个根）。
            // 这与 JSON 分支当年 {json.indexOf("children")} 的缺陷同源，只是发生在 XML 上。
            org.w3c.dom.Element root = doc.getDocumentElement();
            if (root == null) {
                return roots;
            }
            List<org.w3c.dom.Element> sheets = childElements(root, "sheet");
            if (sheets.isEmpty()) {
                // 兼容 sheet 未直接挂在文档根下的个别写法：退化为递归找 sheet，
                // 但每个 sheet 仍只取**直接** topic 子元素，不会重复。
                org.w3c.dom.NodeList found = root.getElementsByTagNameNS(
                        "urn:xmind:xmap:xmlns:content:2.0", "sheet");
                if (found.getLength() == 0) {
                    found = root.getElementsByTagName("sheet");
                }
                for (int i = 0; i < found.getLength(); i++) {
                    sheets.add((org.w3c.dom.Element) found.item(i));
                }
            }
            for (org.w3c.dom.Element sheet : sheets) {
                for (org.w3c.dom.Element topic : childElements(sheet, "topic")) {
                    TopicNode node = buildNodeFromXml(topic);
                    if (node != null) {
                        roots.add(node);
                    }
                }
            }
        } catch (Exception e) {
            // XML 解析异常时静默返回已解析内容（或空列表）
        }
        return roots;
    }

    /**
     * 取某个元素下指定 localName 的**直接**子元素（忽略命名空间前缀）。
     *
     * <p>XMind 8 的子主题结构是 {@code topic > children > topics[type=attached|detached] > topic}，
     * 兄弟层级都靠元素包裹；只有逐层取直接子元素才能得到正确的一层，递归查找会串层。</p>
     *
     * @param parent    父元素
     * @param localName 目标 localName（忽略命名空间前缀，如 "sheet" / "topic" / "topics" / "title"）
     * @return 匹配的直接子元素列表（保持文档顺序）
     */
    private static List<org.w3c.dom.Element> childElements(org.w3c.dom.Element parent, String localName) {
        List<org.w3c.dom.Element> out = Wraps.list();
        if (parent == null) {
            return out;
        }
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                continue;
            }
            String ln = n.getLocalName() != null ? n.getLocalName() : localNameOf(n.getNodeName());
            if (localName.equals(ln)) {
                out.add((org.w3c.dom.Element) n);
            }
        }
        return out;
    }

    /** 去掉 {@code prefix:} 得到 localName（节点未启用命名空间感知时的兜底） */
    private static String localNameOf(String qualifiedName) {
        int colon = qualifiedName == null ? -1 : qualifiedName.indexOf(':');
        return colon >= 0 ? qualifiedName.substring(colon + 1) : qualifiedName;
    }

    /**
     * 从 XML 的 topic 元素递归构建 {@link TopicNode} 树。
     *
     * <p>遍历路径（**逐层取直接子元素**）：{@code topic → title}（取标题）+
     * {@code topic → children → topics → topic*}（取子主题列表，递归构建）。
     * {@code topics} 无论 {@code type=attached}（子主题）还是 {@code type=detached}
     * （浮动主题）都算子节点，与前端渲染器口径一致。</p>
     *
     * @param topicEl XML 中的 topic DOM 元素
     * @return 构建好的主题节点（title 非空，children 可能为空）
     */
    private TopicNode buildNodeFromXml(org.w3c.dom.Element topicEl) {
        String title = directText(topicEl, "title");
        if (title == null || title.isBlank()) {
            title = "Untitled";
        }
        TopicNode node = new TopicNode(title);

        for (org.w3c.dom.Element childrenEl : childElements(topicEl, "children")) {
            for (org.w3c.dom.Element topicsEl : childElements(childrenEl, "topics")) {
                // 只收 子主题(attached) / 浮动主题(detached) / 无 type 的旧写法；
                // 跳过 summary（概要）这类派生容器 —— 与前端渲染器口径一致
                // （实测真实 XMind 8 文件：收 summary 会多出一个「幽灵」节点）。
                String type = topicsEl.getAttribute("type");
                if (type != null && !type.isEmpty()
                        && !"attached".equalsIgnoreCase(type) && !"detached".equalsIgnoreCase(type)) {
                    continue;
                }
                for (org.w3c.dom.Element child : childElements(topicsEl, "topic")) {
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
     * 取某个元素的**直接**子元素文本（忽略命名空间前缀）。
     *
     * <p>用直接子元素而非递归查找：topic 没有 title 时，递归查找会抓到**后代**
     * 主题的 title 冒充自己的标题。</p>
     *
     * @param parent    父元素
     * @param childName 直接子元素名（如 "title"）
     * @return 修剪后的文本；不存在或为空时返回 {@code null}
     */
    private String directText(org.w3c.dom.Element parent, String childName) {
        List<org.w3c.dom.Element> list = childElements(parent, childName);
        if (list.isEmpty()) {
            return null;
        }
        String text = list.get(0).getTextContent();
        return text != null ? text.trim() : null;
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
            sb.append("<p style=\"color:#6b7280\">无法解析思维导图内容（文件可能已损坏、"
                    + "已加密，或不是有效的 XMind 文件）</p>");
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
        List<TopicNode> children = Wraps.list();

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
