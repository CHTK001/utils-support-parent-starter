package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * DjVu 预览提供器（结构 / 元数据 / 书签 / OCR 文本）。
 *
 * <p>SPI 类型：{@code preview-djvu}。<b>不做图像渲染</b>：DjVu 的位图层是 JB2 + IW44
 * （BG44/FG44 分层渐进编码），纯 Java 无现成解码器，自研一个反马赛克/JB2 解码器
 * 的成本与收益完全不成比例——这与 RAW 的取舍同理。</p>
 *
 * <p>能给的（都给全）：</p>
 * <ol>
 *   <li><b>元数据</b>：逐 FORM:DJVU 解 INFO（尺寸 / 版本 / DPI / gamma / flags），
 *       捆绑文档统计页数；</li>
 *   <li><b>目录书签</b>：NAVM 按规范递归解析（描述 + URL + 层级缩进）——电子书类
 *       DjVu 最常被用到的就是目录；</li>
 *   <li><b>OCR 文本</b>：TXTz 解 zlib 后取可读串，TXTa 直取——带文字层的 DjVu
 *       （绝大多数扫描书）能读、能检索；</li>
 *   <li><b>结构清单</b>：IFF85 的 chunk 树（FORM/DIRM/Sjbz/BG44/…），让用户知道
 *       这份文件里到底有什么。</li>
 * </ol>
 *
 * <p>局限写在页面上：不渲染页面图像，需装 DjVuLibre/DjView（免费）看图。</p>
 *
 * <p>IFF85 为大端：4 字节 ID + 4 字节长度 + 数据（奇数长度补 1 字节对齐），
 * FORM 是容器（数据首 4 字节为子类型）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-djvu")
public class DjvuPreviewProvider implements FileStoragePreviewProvider {

    /** 支持的扩展名 */
    private static final Set<String> SUPPORTED_EXTS = Set.of("djvu", "djv");

    /** 最大文件 */
    private static final long MAX_FILE_SIZE = 512L * 1024 * 1024;

    /** IFF 递归深度上限（防构造炸弹） */
    private static final int MAX_DEPTH = 6;

    /** 记录的 chunk 数上限 */
    private static final int MAX_CHUNKS = 400;

    /** 文本层解压上限 */
    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;

    @Override
    public boolean supports(String ext, String mime) {
        if (ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH))) {
            return true;
        }
        return mime != null && mime.toLowerCase(Locale.ENGLISH).contains("djvu");
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) {
        if (content.length == 0) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件为空")).build();
        }
        if (content.length > MAX_FILE_SIZE) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件过大（超过 512 MB），暂不支持预览")).build();
        }
        try {
            return PreviewResult.builder().htmlContent(parse(content)).build();
        } catch (Exception e) {
            return PreviewResult.builder()
                    .htmlContent(unavailableHtml("DjVu 解析失败: " + escape(e.getMessage()))).build();
        }
    }

    /* ------------------------------------------------------------------ */
    /* IFF85 遍历                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 解析主流程。
     *
     * @param content 文件字节
     * @return 完整 HTML
     * @throws Exception 结构性错误
     */
    private String parse(byte[] content) throws Exception {
        if (!looksLikeDjvu(content)) {
            return unavailableHtml("不是 DjVu 文件（缺少 AT&amp;T / FORM / DJVU 标识）");
        }
        State st = new State();
        // 0..3 AT&T / 4..7 FORM / 8..11 BE32 长度 / 12..15 子类型 → chunk 从 16 开始
        walk(content, 16, content.length, 0, st);
        if (st.chunks.isEmpty()) {
            return unavailableHtml("IFF 结构损坏：没有解析到任何 chunk");
        }
        return buildHtml(content.length, st);
    }

    /**
     * 魔数校验：{@code AT&TFORM} + 类型 {@code DJVU/DJVM/DJVI}。
     *
     * @param b 字节
     * @return true = 是 DjVu
     */
    private static boolean looksLikeDjvu(byte[] b) {
        if (b.length < 16) {
            return false;
        }
        String head = new String(b, 0, 4, StandardCharsets.ISO_8859_1);
        String form = new String(b, 4, 4, StandardCharsets.ISO_8859_1);
        String type = new String(b, 12, 4, StandardCharsets.ISO_8859_1);
        return "AT&T".equals(head) && "FORM".equals(form)
                && ("DJVU".equals(type) || "DJVM".equals(type) || "DJVI".equals(type));
    }

    /**
     * 递归遍历 IFF chunk 树。
     *
     * @param d     数据
     * @param off   起始
     * @param end   结束（不含）
     * @param depth 当前深度
     * @param st    状态（出参）
     */
    private static void walk(byte[] d, int off, int end, int depth, State st) {
        if (depth > MAX_DEPTH || st.chunks.size() >= MAX_CHUNKS) {
            return;
        }
        while (off + 8 <= end && st.chunks.size() < MAX_CHUNKS) {
            String id = ascii(d, off, 4);
            int len = be32(d, off + 4);
            int body = off + 8;
            if (len < 0 || body > end || body + len > end) {
                st.truncated = true;
                return;
            }
            if ("FORM".equals(id)) {
                String sub = len >= 4 ? ascii(d, body, 4) : "";
                st.chunks.add("  ".repeat(depth) + "FORM:" + sub + " [" + len + " 字节]");
                if ("DJVU".equals(sub)) {
                    st.pages++;
                }
                walk(d, body + 4, body + len, depth + 1, st);
            } else {
                st.chunks.add("  ".repeat(depth) + id + " [" + len + " 字节]");
                if ("INFO".equals(id) && len >= 10) {
                    st.infos.add(readInfo(d, body));
                } else if (("TXTz".equals(id) || "TXTa".equals(id)) && len > 0 && st.text.isEmpty()) {
                    st.text = readText(d, body, len, "TXTz".equals(id));
                } else if ("NAVM".equals(id) && len > 0 && st.bookmarks.isEmpty()) {
                    readBookmarks(d, body, len, st.bookmarks);
                } else if ("DIRM".equals(id)) {
                    st.hasDirm = true;
                }
            }
            int adv = body + len;
            if ((len & 1) == 1) {
                adv++;
            }
            off = adv;
        }
        if (off < end - 1 && !st.chunks.isEmpty()) {
            // 尾部余量不足一个 chunk 头属正常（可能是补齐字节），只有明显截断才标记
            if (end - off > 8) {
                st.truncated = true;
            }
        }
    }

    /**
     * 读 4 字节 ASCII。
     *
     * @param d   数据
     * @param at  起始
     * @param n   长度
     * @return 字符串
     */
    private static String ascii(byte[] d, int at, int n) {
        if (at < 0 || at + n > d.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = (char) (d[at + i] & 0xFF);
            sb.append(c >= 0x20 && c < 0x7F ? c : '.');
        }
        return sb.toString();
    }

    /**
     * 大端 32 位。
     *
     * @param d  数据
     * @param at 起始
     * @return 值（可能为负，调用方需先做边界判断）
     */
    private static int be32(byte[] d, int at) {
        return ((d[at] & 0xFF) << 24) | ((d[at + 1] & 0xFF) << 16)
                | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
    }

    /**
     * 大端 24 位。
     *
     * @param d  数据
     * @param at 起始
     * @return 值
     */
    private static int be24(byte[] d, int at) {
        return ((d[at] & 0xFF) << 16) | ((d[at + 1] & 0xFF) << 8) | (d[at + 2] & 0xFF);
    }

    /**
     * 大端 16 位。
     *
     * @param d  数据
     * @param at 起始
     * @return 值
     */
    private static int be16(byte[] d, int at) {
        return ((d[at] & 0xFF) << 8) | (d[at + 1] & 0xFF);
    }

    /**
     * 解 INFO chunk（10 字节）。
     *
     * @param d  数据
     * @param at 起始
     * @return 描述
     */
    private static String readInfo(byte[] d, int at) {
        int w = be16(d, at);
        int h = be16(d, at + 2);
        int minor = d[at + 4] & 0xFF;
        int major = d[at + 5] & 0xFF;
        int dpi = be16(d, at + 6);
        int gamma = d[at + 8] & 0xFF;
        int flags = d[at + 9] & 0xFF;
        return w + " × " + h + "，版本 " + major + "." + minor + "，" + dpi + " dpi"
                + "，gamma " + String.format(Locale.ENGLISH, "%.1f", gamma / 10.0)
                + "，flags 0x" + Integer.toHexString(flags).toUpperCase(Locale.ENGLISH);
    }

    /**
     * 读文本层：TXTz 先解 zlib，再取可读串；TXTa 直接取。
     *
     * @param d         数据
     * @param at        起始
     * @param len       长度
     * @param compressed 是否 zlib 压缩
     * @return 文本；没有可读内容返回空串
     */
    private static String readText(byte[] d, int at, int len, boolean compressed) {
        byte[] raw = d;
        int off = at;
        int size = len;
        if (compressed) {
            byte[] inflated = inflate(d, at, len);
            if (inflated == null) {
                return "";
            }
            raw = inflated;
            off = 0;
            size = inflated.length;
        }
        // DjVu 文本记录为 [BE24 长度][UTF-8 串][版本字节] 的循环；结构识别失败则退回可读串扫描
        StringBuilder out = new StringBuilder();
        int p = off;
        int end = off + size;
        int guard = 0;
        while (p + 4 <= end && out.length() < 400_000 && guard++ < 20_000) {
            int n = be24(raw, p);
            if (n <= 0 || p + 3 + n > end) {
                break;
            }
            String s = new String(raw, p + 3, n, StandardCharsets.UTF_8);
            if (isReadable(s)) {
                out.append(s).append('\n');
                p += 3 + n + 1;
                continue;
            }
            break;
        }
        if (out.length() >= 16) {
            return out.toString().replaceAll("\\n{3,}", "\n\n").trim();
        }
        return extractRuns(raw, off, size);
    }

    /**
     * zlib 解压（一次成功即止，封顶 {@link #MAX_TEXT_BYTES}）。
     *
     * @param d   数据
     * @param at  起始
     * @param len 长度
     * @return 解压结果；失败返回 null
     */
    private static byte[] inflate(byte[] d, int at, int len) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(d, at, len);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) {
                        return null;
                    }
                } else {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_TEXT_BYTES) {
                        break;
                    }
                }
            }
            return bos.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    /**
     * 从任意字节里抽出可读文本段（兜底路径）。
     *
     * @param d     数据
     * @param off   起始
     * @param size  长度
     * @return 拼接文本
     */
    private static String extractRuns(byte[] d, int off, int size) {
        StringBuilder sb = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        int end = off + size;
        for (int i = off; i < end && sb.length() < 400_000; i++) {
            char c = (char) (d[i] & 0xFF);
            boolean printable = c >= 0x20 && c < 0x7F || c == '\n' || c == '\r' || c == '\t';
            if (printable) {
                cur.append(c);
            } else {
                flushRun(sb, cur);
            }
        }
        flushRun(sb, cur);
        return sb.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 满足条件的段落追加进结果。
     *
     * @param sb  结果
     * @param cur 当前段
     */
    private static void flushRun(StringBuilder sb, StringBuilder cur) {
        String s = cur.toString().trim();
        cur.setLength(0);
        if (s.length() >= 8 && hasWord(s)) {
            sb.append(s).append('\n');
        }
    }

    /**
     * 是否含「像话」的词（字母数量 ≥ 5）。
     *
     * @param s 串
     * @return true = 是
     */
    private static boolean hasWord(String s) {
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                letters++;
            }
        }
        return letters >= 5;
    }

    /**
     * 是否可读文本。
     *
     * @param s 串
     * @return true = 是
     */
    private static boolean isReadable(String s) {
        if (s.length() < 3) {
            return false;
        }
        int good = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)
                    || ".,;:!?()[]{}'\"-_/\\%+=·—…、，。！？；：（）「」【】".indexOf(c) >= 0) {
                good++;
            }
        }
        return good * 10 >= s.length() * 8;
    }

    /* ------------------------------------------------------------------ */
    /* NAVM 书签                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 解析 NAVM（书签树）。
     *
     * <p>布局：{@code u16 总记录数}，随后记录序列为
     * {@code [u8 子记录数][BE24 描述长][描述][BE24 URL 长][URL]}，
     * 子记录紧跟其父记录（先序）。</p>
     *
     * @param d    数据
     * @param at   起始
     * @param len  长度
     * @param out  书签行（出参，含缩进）
     */
    private static void readBookmarks(byte[] d, int at, int len, List<String> out) {
        int end = at + len;
        if (at + 2 > end) {
            return;
        }
        int total = be16(d, at);
        int[] pos = {at + 2};
        int[] parsed = {0};
        // 顶层可能是多棵树（规范里是一棵，这里循环以兼容变体）
        while (pos[0] + 4 <= end && parsed[0] < Math.max(total, 1) * 2 && out.size() < 500) {
            if (!parseBookmark(d, end, pos, 0, total, parsed, out)) {
                break;
            }
        }
    }

    /**
     * 递归读一条书签及其子记录。
     *
     * @param d        数据
     * @param end      结束位置
     * @param pos      当前位置（出参）
     * @param depth    层级
     * @param total    预期总记录数
     * @param parsed   已解析数（出参）
     * @param out      书签行（出参）
     * @return true = 结构正常
     */
    private static boolean parseBookmark(byte[] d, int end, int[] pos, int depth,
                                         int total, int[] parsed, List<String> out) {
        if (depth > 8 || parsed[0] >= Math.max(total, 1) * 2 || pos[0] + 4 > end) {
            return false;
        }
        int children = d[pos[0]++] & 0xFF;
        int dlen = be24(d, pos[0]);
        pos[0] += 3;
        if (dlen < 0 || dlen > 4000 || pos[0] + dlen + 3 > end) {
            return false;
        }
        String desc = new String(d, pos[0], dlen, StandardCharsets.UTF_8).trim();
        pos[0] += dlen;
        int ulen = be24(d, pos[0]);
        pos[0] += 3;
        if (ulen < 0 || ulen > 4000 || pos[0] + ulen > end) {
            return false;
        }
        String url = new String(d, pos[0], ulen, StandardCharsets.UTF_8).trim();
        pos[0] += ulen;
        parsed[0]++;
        String line = "  ".repeat(depth) + (desc.isEmpty() ? "（无标题）" : desc);
        if (!url.isEmpty()) {
            line += "  → " + url;
        }
        out.add(line);
        boolean ok = true;
        for (int i = 0; i < children && ok; i++) {
            ok = parseBookmark(d, end, pos, depth + 1, total, parsed, out);
        }
        return ok;
    }

    /* ------------------------------------------------------------------ */
    /* 输出构建                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 构建预览 HTML（元数据 + 书签 + 文本 + 结构清单）。
     *
     * @param size 文件字节
     * @param st   解析状态
     * @return 完整 HTML
     */
    private String buildHtml(long size, State st) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
                .append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}")
                .append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}")
                .append("h1{margin:0 0 12px;font-size:20px;font-weight:600}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:8px;font-size:13px}")
                .append(".k{color:#9ca3af}.v{color:#1f2937;word-break:break-all}")
                .append(".sec{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:16px}")
                .append(".sec-title{font-size:13px;font-weight:600;color:#6b7280;margin-bottom:10px}")
                .append(".text{font-size:15px;line-height:1.8;white-space:pre-wrap;word-break:break-word;color:#374151}")
                .append(".tree{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;line-height:1.7;color:#4b5563;white-space:pre-wrap}")
                .append(".notice{margin-bottom:16px;padding:8px 12px;background:#fffbeb;border:1px solid #fde68a;border-radius:8px;color:#92400e;font-size:13px}")
                .append(".empty{color:#6b7280;text-align:center;padding:32px;background:#fff;border:1px dashed #d1d5db;border-radius:12px}")
                .append("</style></head><body>")
                .append("<div class=\"header\"><h1>DjVu 预览（结构 / 元数据 / 文本）</h1><div class=\"grid\">");

        sb.append(cell("文件大小", humanSize(size)));
        sb.append(cell("页数（FORM:DJVU）", String.valueOf(Math.max(st.pages, st.infos.size()))));
        sb.append(cell("chunk 数", String.valueOf(st.chunks.size())));
        sb.append(cell("含目录 DIRM", st.hasDirm ? "是" : "否"));
        if (st.infos.isEmpty()) {
            sb.append(cell("页面尺寸", "未取到 INFO"));
        } else {
            sb.append(cell("页面尺寸", st.infos.get(0)));
            if (st.infos.size() > 1) {
                sb.append(cell("其余页面 INFO", String.valueOf(st.infos.size() - 1) + " 条"));
            }
        }
        sb.append("</div></div>");

        sb.append("<div class=\"notice\">本预览<b>不渲染页面图像</b>：DjVu 的位图层是 JB2 + IW44 分层编码，")
                .append("纯 Java 无现成解码器，自研性价比极低。看图请用免费的 DjVuLibre / DjView。")
                .append("此处给出元数据、目录书签、OCR 文本与结构清单——带文字层的扫描书可直接读与检索。")
                .append(st.truncated ? " 该文件结构有截断，已展示可解析的部分。" : "")
                .append("</div>");

        if (!st.bookmarks.isEmpty()) {
            sb.append("<div class=\"sec\"><div class=\"sec-title\">目录书签（NAVM，")
                    .append(st.bookmarks.size()).append(" 条）</div><div class=\"text\">")
                    .append(escape(String.join("\n", st.bookmarks))).append("</div></div>");
        }

        if (st.text.isEmpty()) {
            sb.append("<div class=\"empty\">未找到文字层（TXTa/TXTz）。纯扫描无 OCR 的 DjVu 无法取字，")
                    .append("需 OCR 后才能出文本。</div>");
        } else {
            sb.append("<div class=\"sec\"><div class=\"sec-title\">文字层（")
                    .append(st.text.length()).append(" 字符）</div><div class=\"text\">")
                    .append(escape(st.text)).append("</div></div>");
        }

        sb.append("<div class=\"sec\"><div class=\"sec-title\">IFF 结构清单")
                .append(st.chunks.size() >= MAX_CHUNKS ? "（已截断至 " + MAX_CHUNKS + " 条）" : "")
                .append("</div><div class=\"tree\">");
        for (String c : st.chunks) {
            sb.append(escape(c)).append('\n');
        }
        sb.append("</div></div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 指标单元格。
     *
     * @param k 键
     * @param v 值
     * @return HTML 片段
     */
    private static String cell(String k, String v) {
        return "<div><span class=\"k\">" + k + "</span><br><span class=\"v\">" + v + "</span></div>";
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
                + "</style></head><body><div class=\"box\">" + message + "</div></body></html>";
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
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ENGLISH, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 解析状态。
     */
    private static final class State {
        /** 结构清单 */
        final List<String> chunks = Wraps.list();
        /** 各页 INFO */
        final List<String> infos = Wraps.list();
        /** 书签行 */
        final List<String> bookmarks = Wraps.list();
        /** 文字层 */
        String text = "";
        /** 页数 */
        int pages;
        /** 是否有 DIRM（捆绑文档） */
        boolean hasDirm;
        /** 结构是否截断/损坏 */
        boolean truncated;
    }
}
