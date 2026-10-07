package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import org.xerial.snappy.Snappy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Apple iWork 文档（Pages / Numbers / Keynote）预览提供器。
 *
 * <p>SPI 类型：{@code preview-iwork}。iWork 三代容器一次覆盖：</p>
 *
 * <ol>
 *   <li><b>iWork '09</b>：{@code index.xml}（Pages）/ {@code index.apxl}（Keynote）——
 *       APXL XML，直接抽文本节点；</li>
 *   <li><b>2013+</b>：{@code Index/*.iwa} —— Snappy 压缩的 Protobuf 流
 *       （4 字节头：0x00 + 3 字节小端压缩长度），解压后按长度前缀扫描出字符串；</li>
 *   <li><b>2018+</b>：Keynote 再套一层 {@code Index.zip}，递归一层即可。</li>
 * </ol>
 *
 * <p>另有两件保底：</p>
 * <ul>
 *   <li><b>封面图</b>：容器里的 {@code preview.jpg / preview-web.jpg / QuickLook/Thumbnail.jpg}
 *       直接内嵌展示——多数场景下用户只想看看长什么样，这一张图就够；</li>
 *   <li><b>非包文件</b>：{@code .key} 也是 PGP/许可证私钥的常用扩展名。文件不是 ZIP 时
 *       不报错，而是按纯文本展示，避免把一份密钥文件渲染成「解析失败」。</li>
 * </ul>
 *
 * <p><b>局限</b>：IWA 没有公开 schema，文本抽取是「解压后按长度前缀扫描可读串」，
 * 会漏掉压缩到非字符串字段的数值语义（表格数字大多在），也会带出少量样式名/资源 ID
 * （已按白噪过滤）。定位于「能读、能检索」的审阅级预览，不是排版级还原。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-iwork")
public class IworkPreviewProvider implements FileStoragePreviewProvider {

    /** 支持的扩展名 */
    private static final Set<String> SUPPORTED_EXTS = Set.of("pages", "numbers", "key");

    /** 最大文件 */
    private static final long MAX_FILE_SIZE = 256L * 1024 * 1024;

    /** 单个部件参与解析的上限 */
    private static final int MAX_PART_SIZE = 32 * 1024 * 1024;

    /** 封面图上限 */
    private static final int MAX_PREVIEW_BYTES = 6 * 1024 * 1024;

    /** 抽字总量上限 */
    private static final int MAX_TOTAL_CHARS = 400_000;

    /** 展示的部件数上限 */
    private static final int MAX_PARTS_SHOWN = 40;

    /** 封面图候选（按优先级） */
    private static final String[] PREVIEW_IMAGES = {
            "preview.jpg", "preview-web.jpg", "preview.png", "Preview.jpg",
            "QuickLook/Thumbnail.jpg", "quicklook/thumbnail.jpg"};

    /** 明显的噪音串（资源标识/命名空间/样式 ID），抽字时丢弃 */
    private static final List<String> JUNK_PREFIXES = List.of(
            "com.apple", "http://", "https://", "xmlns", "NS.", "TS.", "TST.", "TSP.",
            "IWA", "Apple", "Lucida", "Helvetica", "ArialMT", ".png", ".jpg", ".ttf",
            "iWork", "Keynote", "Pages", "Numbers");

    @Override
    public boolean supports(String ext, String mime) {
        if (ext == null) {
            return false;
        }
        String e = ext.toLowerCase(Locale.ENGLISH);
        if (SUPPORTED_EXTS.contains(e)) {
            return true;
        }
        return mime != null && mime.contains("iwork") || mime != null && mime.contains("pages");
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
                    .htmlContent(unavailableHtml("iWork 解析失败: " + escape(e.getMessage()))).build();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 解析主流程                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 解析 iWork 文档。
     *
     * @param content 文件字节
     * @return 完整 HTML
     * @throws IOException 读取失败
     */
    private String parse(byte[] content) throws IOException {
        if (!looksLikeZip(content)) {
            return plainTextFallback(content);
        }
        Map<String, byte[]> parts = readZip(content, 0);
        if (parts.isEmpty()) {
            return unavailableHtml("容器损坏或没有可解析的部件（不是合法的 iWork 文档）");
        }

        // 1) 封面图
        byte[] cover = findCover(parts);

        // 2) 文本部件
        List<Section> sections = Wraps.list();
        int iwaCount = 0;
        for (Map.Entry<String, byte[]> e : parts.entrySet()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            byte[] data = e.getValue();
            if (name.endsWith("index.zip")) {
                // 2018+ Keynote 再套一层
                for (Map.Entry<String, byte[]> nested : readZip(data, 1).entrySet()) {
                    if (nested.getKey().toLowerCase(Locale.ROOT).endsWith(".iwa")) {
                        iwaCount++;
                        addIwaSection(sections, nested.getKey(), nested.getValue());
                    }
                }
            } else if (name.endsWith(".iwa")) {
                iwaCount++;
                addIwaSection(sections, e.getKey(), data);
            } else if (name.endsWith("index.xml") || name.endsWith(".apxl") || name.endsWith("index.apxl")) {
                String text = extractXmlText(data);
                if (!text.isBlank()) {
                    sections.add(new Section(fileName(e.getKey()) + "（APXL）", text));
                }
            }
        }

        return buildHtml(content.length, cover, sections, parts.size(), iwaCount);
    }

    /**
     * 是否 ZIP 容器。
     *
     * @param b 字节
     * @return true = PK 开头
     */
    private static boolean looksLikeZip(byte[] b) {
        return b.length > 4 && b[0] == 'P' && b[1] == 'K'
                && (b[2] == 3 || b[2] == 5 || b[2] == 7);
    }

    /**
     * 非 ZIP：按纯文本兜底展示（{@code .key} 常被用作 PGP/许可证密钥文件）。
     *
     * @param content 文件字节
     * @return 完整 HTML
     */
    private String plainTextFallback(byte[] content) {
        String text = new String(content, 0, Math.min(content.length, 200_000), StandardCharsets.UTF_8);
        // 二进制特征过多就不展示原文，只给提示
        int sample = Math.min(text.length(), 4000);
        int bad = 0;
        for (int i = 0; i < sample; i++) {
            char c = text.charAt(i);
            if (c < 0x09 || (c > 0x0D && c < 0x20)) {
                bad++;
            }
        }
        if (sample == 0 || bad * 10 > sample) {
            return unavailableHtml("不是 iWork 文档包（也不是可读文本）");
        }
        return buildHtml(content.length, null,
                List.of(new Section("原文（非 iWork 包，按纯文本展示）", text)), 1, 0);
    }

    /**
     * 读 ZIP（可递归一层，防炸弹：条目数与单件大小双上限）。
     *
     * @param data  ZIP 字节
     * @param depth 递归层级（0/1）
     * @return 部件名 → 内容
     * @throws IOException 不是合法 ZIP
     */
    private static Map<String, byte[]> readZip(byte[] data, int depth) throws IOException {
        Map<String, byte[]> out = Wraps.linkedMap();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null && out.size() < 500) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/")) {
                    name = name.substring(1);
                }
                byte[] body = readCapped(zis, MAX_PART_SIZE);
                if (body != null && depth + (name.toLowerCase(Locale.ROOT).endsWith("index.zip") ? 1 : 0) <= 1) {
                    out.put(name, body);
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("不是合法的 iWork（ZIP）容器", e);
        }
        return out;
    }

    /**
     * 读条目并限制大小。
     *
     * @param zis   流
     * @param limit 上限
     * @return 内容；超限返回 null
     * @throws IOException 读失败
     */
    private static byte[] readCapped(ZipInputStream zis, int limit) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = zis.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                return null;
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * 找封面图。
     *
     * @param parts 部件表
     * @return JPEG/PNG 字节；无则 null
     */
    private static byte[] findCover(Map<String, byte[]> parts) {
        for (String candidate : PREVIEW_IMAGES) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                if (e.getKey().equalsIgnoreCase(candidate)
                        || e.getKey().toLowerCase(Locale.ROOT).endsWith("/" + candidate.toLowerCase(Locale.ROOT))) {
                    if (e.getValue().length <= MAX_PREVIEW_BYTES && e.getValue().length > 0) {
                        return e.getValue();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 取部件简单名。
     *
     * @param path 部件全名
     * @return 简单名
     */
    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /* ------------------------------------------------------------------ */
    /* IWA：Snappy + Protobuf                                              */
    /* ------------------------------------------------------------------ */

    /**
     * 解一个 .iwa 部件并追加为章节。
     *
     * @param sections 章节表（出参）
     * @param name     部件名
     * @param data     部件字节
     */
    private void addIwaSection(List<Section> sections, String name, byte[] data) {
        byte[] proto = inflateIwa(data);
        if (proto == null || proto.length == 0) {
            return;
        }
        String text = scanStrings(proto);
        if (!text.isBlank()) {
            sections.add(new Section(fileName(name), text));
        }
    }

    /**
     * 解 IWA 的 Snappy 分帧：4 字节头（0x00 + 3 字节小端压缩长度）+ Snappy 块，
     * 逐块解压后拼接。任一块失败即停（保留已解出的部分，best-effort）。
     *
     * <p>兜底：若分帧解析一无所获，说明它是未压缩的 protobuf 流，原样返回。</p>
     *
     * @param data .iwa 字节
     * @return protobuf 流；完全解不开返回 null
     */
    private static byte[] inflateIwa(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        boolean any = false;
        while (pos + 4 <= data.length) {
            int type = data[pos] & 0xFF;
            int size = (data[pos + 1] & 0xFF) | ((data[pos + 2] & 0xFF) << 8) | ((data[pos + 3] & 0xFF) << 16);
            pos += 4;
            if (type != 0 || size <= 0 || pos + size > data.length) {
                break;
            }
            byte[] block = new byte[size];
            System.arraycopy(data, pos, block, 0, size);
            pos += size;
            try {
                out.write(Snappy.uncompress(block));
                any = true;
            } catch (IOException e) {
                break;
            }
        }
        if (any && out.size() > 0) {
            return out.toByteArray();
        }
        // 兜底：直接当 protobuf 流（未压缩形态）
        if (data.length > 4 && (data[0] & 0xFF) != 0) {
            return data;
        }
        return null;
    }

    /**
     * 从 protobuf 流里扫描长度前缀字符串。
     *
     * <p>做法：凡字节低 3 位为 {@code 010}（wire type = 2，length-delimited）即当作候选字段头，
     * 随后是 varint 长度与负载；负载能解成 UTF-8 且「可读字符占比」达标就收下。
     * 这类启发式会带上少量样式名/资源 ID，交由 {@link #isNoise(String)} 过滤。</p>
     *
     * @param proto protobuf 字节
     * @return 拼接文本
     */
    private static String scanStrings(byte[] proto) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = proto.length;
        String prev = "";
        while (i < n - 2 && sb.length() < MAX_TOTAL_CHARS) {
            int head = proto[i] & 0xFF;
            if ((head & 0x07) != 0x02 || head < 0x0A || head > 0xF2) {
                i++;
                continue;
            }
            // varint 长度（最多 3 字节，覆盖到 2 MB）
            int len = 0;
            int shift = 0;
            int j = i + 1;
            boolean ok = false;
            for (int k = 0; k < 3 && j < n; k++, j++) {
                int b = proto[j] & 0xFF;
                len |= (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    ok = true;
                    break;
                }
            }
            int start = j + 1;
            if (ok && len >= 2 && len <= 4000 && start + len <= n) {
                String s = new String(proto, start, len, StandardCharsets.UTF_8);
                if (isReadable(s) && !isNoise(s) && !s.equals(prev)) {
                    sb.append(s);
                    // 段落级串后换行，词级碎片直接相连
                    if (s.length() >= 16 || endsSentence(s)) {
                        sb.append('\n');
                    }
                    prev = s;
                }
                i = start + len;
                continue;
            }
            i++;
        }
        return sb.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 是否以句末标点收尾（用于段落断行）。
     *
     * @param s 串
     * @return true = 是
     */
    private static boolean endsSentence(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '。' || c == '!' || c == '！' || c == '?' || c == '？'
                || c == ';' || c == '；' || c == '…';
    }

    /**
     * 是否是「可读文本」：至少 2 个字符，且字母/汉字/数字占多数。
     *
     * @param s 串
     * @return true = 可读
     */
    private static boolean isReadable(String s) {
        if (s.length() < 2 || s.length() > 4000) {
            return false;
        }
        int good = 0;
        int total = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (Character.isLetterOrDigit(c) || ".,;:!?()[]{}'\"-_/\\%+=·—…、，。！？；：（）「」【】".indexOf(c) >= 0) {
                good++;
            }
        }
        if (total < 2) {
            return false;
        }
        // 纯哈希/UUID/资源 ID 形态直接丢
        if (s.length() > 24 && s.indexOf(' ') < 0 && !containsCjk(s)) {
            return false;
        }
        return good * 10 >= total * 7;
    }

    /**
     * 是否含 CJK。
     *
     * @param s 串
     * @return true = 含
     */
    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3040 && c <= 0x30FF) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否噪音（命名空间/样式名/资源前缀）。
     *
     * @param s 串
     * @return true = 丢弃
     */
    private static boolean isNoise(String s) {
        for (String p : JUNK_PREFIXES) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* APXL（iWork '09）                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * 从 APXL/XML 抽文本：去标签、解实体、压空白。只取前 4 MB（APXL 后段多是样式表）。
     *
     * @param xml XML 字节
     * @return 文本
     */
    private static String extractXmlText(byte[] xml) {
        String raw = new String(xml, 0, Math.min(xml.length, 4 * 1024 * 1024), StandardCharsets.UTF_8);
        int body = raw.indexOf('>');
        String head = body > 0 ? raw.substring(0, body) : "";
        String rest = body > 0 ? raw.substring(body + 1) : raw;
        String text = rest.replaceAll("(?s)<[^>]+>", " ");
        text = decodeEntities(text);
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        text = text.replaceAll(" ?\\n ?", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        String decl = decodeEntities(head.replaceAll("(?s)\\s+", " "));
        return (decl.isBlank() ? "" : "") + text.trim();
    }

    /**
     * 实体解码（命名 + 数字实体）。
     *
     * @param s 原文
     * @return 解码结果
     */
    private static String decodeEntities(String s) {
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
     * 数字实体转码点。
     *
     * @param ent 实体体
     * @return 码点；非数字返回 null
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

    /* ------------------------------------------------------------------ */
    /* 输出构建                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 构建预览 HTML（封面 + 章节文本 + 部件清单）。
     *
     * @param size     文件字节
     * @param cover    封面图（可为 null）
     * @param sections 章节
     * @param partNum  部件数
     * @param iwaNum   IWA 部件数
     * @return 完整 HTML
     */
    private String buildHtml(long size, byte[] cover, List<Section> sections, int partNum, int iwaNum) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
                .append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}")
                .append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}")
                .append("h1{margin:0 0 12px;font-size:20px;font-weight:600}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:8px;font-size:13px}")
                .append(".k{color:#9ca3af}.v{color:#1f2937;word-break:break-all}")
                .append(".shot{text-align:center;background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:16px;margin-bottom:20px}")
                .append(".shot img{max-width:100%;max-height:70vh;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,.12)}")
                .append(".cap{margin-top:10px;font-size:12px;color:#6b7280}")
                .append(".sec{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:16px}")
                .append(".sec-title{font-size:13px;font-weight:600;color:#6b7280;margin-bottom:10px}")
                .append(".text{font-size:15px;line-height:1.8;white-space:pre-wrap;word-break:break-word;color:#374151}")
                .append(".notice{margin-bottom:16px;padding:8px 12px;background:#fffbeb;border:1px solid #fde68a;border-radius:8px;color:#92400e;font-size:13px}")
                .append(".empty{color:#6b7280;text-align:center;padding:32px;background:#fff;border:1px dashed #d1d5db;border-radius:12px}")
                .append("</style></head><body>")
                .append("<div class=\"header\"><h1>iWork 文档预览</h1><div class=\"grid\">");
        sb.append("<div><span class=\"k\">文件大小</span><br><span class=\"v\">").append(humanSize(size)).append("</span></div>")
                .append("<div><span class=\"k\">容器部件</span><br><span class=\"v\">").append(partNum).append("</span></div>")
                .append("<div><span class=\"k\">IWA 部件</span><br><span class=\"v\">").append(iwaNum).append("</span></div>")
                .append("<div><span class=\"k\">可读章节</span><br><span class=\"v\">").append(sections.size()).append("</span></div>")
                .append("</div></div>");
        if (cover != null) {
            String mime = cover.length > 1 && cover[0] == (byte) 0x89 ? "image/png" : "image/jpeg";
            sb.append("<div class=\"shot\"><img alt=\"preview\" src=\"data:").append(mime).append(";base64,")
                    .append(Base64.getEncoder().encodeToString(cover)).append("\">")
                    .append("<div class=\"cap\">封面图（容器内 preview.jpg）</div></div>");
        }
        sb.append("<div class=\"notice\">本预览为审阅级：APXL 抽文本节点、IWA 解 Snappy 后按长度前缀扫字符串，")
                .append("不还原排版；表格数字与正文大体可读，样式/资源 ID 已过滤。</div>");
        if (sections.isEmpty()) {
            sb.append("<div class=\"empty\">未能抽到文本（可能只有图片或结构化数据）。");
            if (cover == null) {
                sb.append(" 容器内也没有封面图。");
            }
            sb.append("</div>");
        } else {
            int shown = 0;
            for (Section s : sections) {
                if (shown++ >= MAX_PARTS_SHOWN || sb.length() > MAX_TOTAL_CHARS) {
                    sb.append("<div class=\"notice\">仅展示前 ").append(MAX_PARTS_SHOWN).append(" 个文本部件。</div>");
                    break;
                }
                sb.append("<div class=\"sec\"><div class=\"sec-title\">").append(escape(s.title))
                        .append("</div><div class=\"text\">").append(escape(s.text)).append("</div></div>");
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

    /**
     * 文本章节。
     *
     * @param title 来源部件
     * @param text  文本
     */
    private record Section(String title, String text) {
    }
}
