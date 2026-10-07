package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * XPS / OXPS 固定版式文档预览提供器。
 *
 * <p>SPI 类型：{@code preview-xps}。不依赖第三方库，按 OPC（ZIP + XML）结构解析：</p>
 * <ul>
 *   <li>读取 {@code FixedDocumentSequence.xaml} 得到页面部件清单（PageContent/@Source）；</li>
 *   <li>逐页解析 {@code Glyphs/@UnicodeString} 按版面顺序抽取正文（OriginY 变化处断行）；</li>
 *   <li>读取 {@code docProps/core.xml} 的标题与作者作为元数据。</li>
 * </ul>
 * <p>XPS 是固定版式，本提供器输出的是「逐页文本」而非图形重绘——与 EPUB 同一档的
 * 文本抽取能力，足以覆盖审阅/检索场景；需要像素级还原时另行接渲染引擎。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-xps")
public class XpsPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("xps", "oxps"); // 支持exts
    private static final long MAX_FILE_SIZE = 256L * 1024 * 1024; // 最大文件大小
    /** 单个部件参与解析的上限（超大图纸页跳过，避免内存被撑爆） */
    private static final int MAX_PART_SIZE = 8 * 1024 * 1024;
    /** 展示页数上限 */
    private static final int MAX_PAGES = 40;
    /** 单页抽字上限 */
    private static final int MAX_PAGE_CHARS = 6000;

    /** Glyphs 元素 */
    private static final Pattern GLYPHS = Pattern.compile("<Glyphs\\b[^>]*>", Pattern.DOTALL);
    /** PageContent 元素 */
    private static final Pattern PAGE_CONTENT = Pattern.compile("<PageContent\\b[^>]*>", Pattern.DOTALL);
    /** 属性键值对 */
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_][\\w:.-]*)\\s*=\\s*\"([^\"]*)\"");
    /** 核心属性元素（dc:title / dc:creator 等，捕获标签名与值） */
    private static final Pattern CORE_PROP = Pattern.compile(
            "<(dc:title|dc:creator|cp:lastModifiedBy)\\b[^>]*>([^<]*)<");

    @Override
    public boolean supports(String ext, String mime) {
        if (ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH))) {
            return true;
        }
        return mime != null && mime.toLowerCase(Locale.ENGLISH).contains("oxps")
                || mime != null && mime.toLowerCase(Locale.ENGLISH).endsWith("xps");
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        if (content.length == 0) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件为空")).build();
        }
        if (content.length > MAX_FILE_SIZE) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件过大（超过 256 MB），暂不支持预览")).build();
        }
        try {
            return PreviewResult.builder().htmlContent(parse(content)).build();
        } catch (Exception e) {
            return PreviewResult.builder()
                    .htmlContent(unavailableHtml("XPS 解析失败: " + escape(e.getMessage()))).build();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 解析主流程                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 解析 XPS 并产出预览 HTML。
     *
     * @param content 文件字节
     * @return 完整 HTML
     * @throws IOException 不是合法 OPC 包时抛出
     */
    private String parse(byte[] content) throws IOException {
        Map<String, byte[]> parts = readPackage(content);

        // 1) 文档序列 → 页面部件清单
        String seqPart = findPart(parts, "FixedDocumentSequence.xaml");
        List<String> pageParts = Wraps.list();
        if (seqPart != null) {
            String seq = new String(parts.get(seqPart), java.nio.charset.StandardCharsets.UTF_8);
            Matcher m = PAGE_CONTENT.matcher(seq);
            while (m.find()) {
                String src = attr(m.group(), "Source");
                if (src != null) {
                    String resolved = resolve(seqPart, src);
                    if (parts.containsKey(resolved)) {
                        pageParts.add(resolved);
                    }
                }
            }
        }
        // 2) 兜底：没有序列部件时，按包内页部件自然顺序
        if (pageParts.isEmpty()) {
            for (String name : parts.keySet()) {
                String simple = name.substring(name.lastIndexOf('/') + 1);
                if (simple.startsWith("Page") && simple.endsWith(".xaml")) {
                    pageParts.add(name);
                }
            }
        }

        // 3) 抽正文
        List<String> pages = Wraps.list();
        int glyphCount = 0;
        for (int i = 0; i < pageParts.size() && pages.size() < MAX_PAGES; i++) {
            String xml = new String(parts.get(pageParts.get(i)), java.nio.charset.StandardCharsets.UTF_8);
            String text = extractPageText(xml);
            glyphCount += text.length();
            if (!text.isBlank()) {
                pages.add(text);
            }
        }

        Map<String, String> meta = readCoreProps(parts);
        return buildHtml(content.length, meta, pages, pageParts.size(), glyphCount);
    }

    /**
     * 读 OPC 包：只保留解析用得上的 XML 部件（xaml/xml/rels），单件超限即跳过。
     *
     * @param content 文件字节
     * @return 部件名（已去掉前导 /）→ 内容
     * @throws IOException 不是 ZIP 时抛出
     */
    private static Map<String, byte[]> readPackage(byte[] content) throws IOException {
        Map<String, byte[]> parts = Wraps.linkedMap();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null && parts.size() < 4000) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/")) {
                    name = name.substring(1);
                }
                String lower = name.toLowerCase(Locale.ROOT);
                boolean wanted = lower.endsWith(".xaml") || lower.endsWith(".xml") || lower.endsWith(".rels");
                if (!wanted || entry.getSize() > MAX_PART_SIZE) {
                    continue;
                }
                byte[] data = zis.readAllBytes();
                if (data.length <= MAX_PART_SIZE) {
                    parts.put(name, data);
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("不是合法的 XPS（OPC）包", e);
        }
        if (parts.isEmpty()) {
            throw new IOException("XPS 包内没有可用的页面部件");
        }
        return parts;
    }

    /**
     * 按简单文件名找部件（返回包内完整部件名）。
     *
     * @param parts  部件表
     * @param simple 简单名
     * @return 部件名；无则 null
     */
    private static String findPart(Map<String, byte[]> parts, String simple) {
        for (String name : parts.keySet()) {
            if (name.equals(simple) || name.endsWith("/" + simple)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 解析相对部件引用（OPC 内部 URI：去掉 query/fragment，处理 ../）。
     *
     * @param basePart 引用方部件名
     * @param rel      相对引用
     * @return 包内规范部件名
     */
    private static String resolve(String basePart, String rel) {
        String r = rel;
        int cut = Math.min(idxOfAny(r, "?#"), r.length());
        r = r.substring(0, cut);
        if (r.startsWith("/")) {
            return r.substring(1);
        }
        String dir = basePart.contains("/") ? basePart.substring(0, basePart.lastIndexOf('/')) : "";
        Deque<String> stack = Wraps.deque();
        if (!dir.isEmpty()) {
            for (String seg : dir.split("/")) {
                if (!seg.isEmpty()) {
                    stack.addLast(seg);
                }
            }
        }
        for (String seg : r.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                continue;
            }
            stack.addLast(seg);
        }
        return String.join("/", stack);
    }

    /**
     * 找任意一个字符的位置（无则返回 fallback）。
     *
     * @param s        串
     * @param chars    字符集
     * @param fallback 未命中时返回值
     * @return 最小下标
     */
    private static int idxOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                best = i;
                break;
            }
        }
        return best < 0 ? s.length() : best;
    }

    /* ------------------------------------------------------------------ */
    /* 页面抽字                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 从一页 XPS XML 抽出可读文本。
     *
     * <p>按文档顺序遍历 {@code Glyphs}，同基线（OriginY 相近）的片段连成一行，
     * 基线变化则断行——这样段落与表格列不会糊成一坨。</p>
     *
     * @param pageXml 页面 XPS XML
     * @return 页面文本
     */
    private static String extractPageText(String pageXml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = GLYPHS.matcher(pageXml);
        Double lastY = null;
        int seen = 0;
        while (m.find() && sb.length() < MAX_PAGE_CHARS) {
            String tag = m.group();
            String uni = attr(tag, "UnicodeString");
            if (uni == null || uni.isEmpty()) {
                continue;
            }
            Double y = doubleAttr(tag, "OriginY");
            if (lastY != null && y != null && Math.abs(y - lastY) > 1.5D) {
                sb.append('\n');
            }
            lastY = y != null ? y : lastY;
            sb.append(decodeXml(uni));
            seen++;
        }
        if (seen == 0) {
            return "";
        }
        return sb.length() > MAX_PAGE_CHARS ? sb.substring(0, MAX_PAGE_CHARS) : sb.toString();
    }

    /**
     * 取元素属性值（XML 实体已解码）。
     *
     * @param tag 元素原文
     * @param key 属性名
     * @return 属性值；无则 null
     */
    private static String attr(String tag, String key) {
        Matcher m = ATTR.matcher(tag);
        while (m.find()) {
            if (m.group(1).equals(key)) {
                return decodeXml(m.group(2));
            }
        }
        return null;
    }

    /**
     * 取数值属性。
     *
     * @param tag 元素原文
     * @param key 属性名
     * @return 数值；无法解析返回 null
     */
    private static Double doubleAttr(String tag, String key) {
        String v = attr(tag, key);
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * XML 实体解码（命名 + 数字实体）。
     *
     * @param s 原文
     * @return 解码结果
     */
    private static String decodeXml(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                sb.append(c);
                i++;
                continue;
            }
            String ent = s.substring(i + 1, semi);
            Integer cp = switch (ent) {
                case "amp" -> (int) '&';
                case "lt" -> (int) '<';
                case "gt" -> (int) '>';
                case "quot" -> (int) '"';
                case "apos" -> (int) '\'';
                case "nbsp" -> (int) ' ';
                default -> numeric(ent);
            };
            if (cp != null && cp >= 0 && cp <= 0x10FFFF) {
                sb.appendCodePoint(cp);
                i = semi + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 数字实体（{@code 123} / {@code x1F600}）转码点。
     *
     * @param ent 实体体
     * @return 码点；非数字实体返回 null
     */
    private static Integer numeric(String ent) {
        try {
            if (ent.startsWith("#x") || ent.startsWith("#X")) {
                return Integer.parseInt(ent.substring(2), 16);
            }
            if (ent.startsWith("#")) {
                return Integer.parseInt(ent.substring(1));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /**
     * 读核心属性（标题 / 作者 / 最后修改人）。
     *
     * @param parts 部件表
     * @return 元数据（可能为空）
     */
    private static Map<String, String> readCoreProps(Map<String, byte[]> parts) {
        Map<String, String> out = Wraps.linkedMap();
        String core = findPart(parts, "core.xml");
        if (core == null) {
            return out;
        }
        String xml = new String(parts.get(core), java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> byTag = Map.of(
                "dc:title", "标题",
                "dc:creator", "作者",
                "cp:lastModifiedBy", "最后修改");
        Matcher m = CORE_PROP.matcher(xml);
        while (m.find()) {
            String v = decodeXml(m.group(2)).trim();
            String name = byTag.get(m.group(1));
            if (name != null && !v.isEmpty()) {
                out.put(name, v);
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* 输出构建                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 构建预览 HTML（元数据 + 逐页文本）。
     *
     * @param size      文件字节
     * @param meta      核心属性
     * @param pages     逐页文本
     * @param pageCount 包内页数
     * @param glyph     抽出的字符总数
     * @return 完整 HTML
     */
    private String buildHtml(long size, Map<String, String> meta, List<String> pages,
                             int pageCount, int glyph) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
                .append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}")
                .append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}")
                .append("h1{margin:0 0 12px;font-size:20px;font-weight:600}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:8px;font-size:13px}")
                .append(".k{color:#9ca3af}.v{color:#1f2937;word-break:break-all}")
                .append(".notice{margin-top:12px;padding:8px 12px;background:#fffbeb;border:1px solid #fde68a;border-radius:8px;color:#92400e;font-size:13px}")
                .append(".page{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:24px;margin-bottom:16px}")
                .append(".page-title{font-size:13px;font-weight:600;color:#6b7280;margin-bottom:12px}")
                .append(".text{font-size:15px;line-height:1.8;white-space:pre-wrap;word-break:break-word;color:#374151}")
                .append(".empty{color:#6b7280;text-align:center;padding:40px}")
                .append("</style></head><body>")
                .append("<div class=\"header\"><h1>XPS 固定版式文档预览</h1>")
                .append("<div class=\"grid\">");
        meta.forEach((k, v) -> sb.append("<div><span class=\"k\">").append(escape(k))
                .append("</span><br><span class=\"v\">").append(escape(v)).append("</span></div>"));
        sb.append("<div><span class=\"k\">文件大小</span><br><span class=\"v\">").append(humanSize(size)).append("</span></div>")
                .append("<div><span class=\"k\">页数</span><br><span class=\"v\">").append(pageCount).append("</span></div>")
                .append("<div><span class=\"k\">抽字</span><br><span class=\"v\">").append(glyph).append(" 字</span></div>")
                .append("</div>");
        if (pageCount > pages.size()) {
            sb.append("<div class=\"notice\">共 ").append(pageCount).append(" 页，仅展示前 ")
                    .append(pages.size()).append(" 页的文本。</div>");
        }
        sb.append("</div>");
        if (pages.isEmpty()) {
            sb.append("<div class=\"page\"><div class=\"empty\">未能从文档中抽取到文本（可能为纯图形页面）</div></div>");
        } else {
            for (int i = 0; i < pages.size(); i++) {
                sb.append("<div class=\"page\"><div class=\"page-title\">第 ").append(i + 1)
                        .append(" 页</div><div class=\"text\">").append(escape(pages.get(i)))
                        .append("</div></div>");
            }
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 构建不可预览页面。
     *
     * @param message 提示信息
     * @return 完整 HTML
     */
    private String unavailableHtml(String message) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><style>"
                + "body{margin:0;background:#f8f9fa;color:#666;display:flex;justify-content:center;align-items:center;min-height:100vh;font-family:sans-serif}"
                + ".box{text-align:center;padding:40px;border:2px dashed #ddd;border-radius:12px}"
                + "</style></head><body><div class=\"box\">" + escape(message) + "</div></body></html>";
    }

    /**
     * HTML 转义。
     *
     * @param text 原始文本
     * @return 转义后文本
     */
    private String escape(String text) {
        return text == null ? "" : StringUtils.escapeHtml(text);
    }

    /**
     * 可读文件大小。
     *
     * @param bytes 字节数
     * @return 可读大小
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
}
