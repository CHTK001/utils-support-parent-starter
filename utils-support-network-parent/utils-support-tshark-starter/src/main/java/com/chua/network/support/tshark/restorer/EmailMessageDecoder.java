package com.chua.network.support.tshark.restorer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件报文（SMTP DATA / POP3 RETR / IMAP FETCH）的 MIME 解析与还原。
 *
 * <p><b>为什么单独一层</b>：原来的 email 还原器只按首行关键字判断，命中就把整段
 * 原样加个前缀返回。真正的邮件正文是这样两段：先一组头（Subject / From / MIME-Version /
 * Content-Type / Content-Transfer-Encoding / Content-Disposition），空行之后才是正文，
 * 正文还可能被 base64 或 quoted-printable 编码、按 boundary 切成多个 part。
 * 只回显首行等于什么都看不到，所以这里做三件事：</p>
 * <ol>
 *   <li>拆头与正文，支持折行（续行以空格或 Tab 开头）；</li>
 *   <li>按 RFC 2047 还原 {@code =?UTF-8?B?...?=} 这类编码字；</li>
 *   <li>按 Content-Transfer-Encoding 解码正文，并列出附件（文件名 / 类型 / 字节数）。</li>
 * </ol>
 *
 * <p><b>边界</b>：这里只做单帧内能拿到的字节。一封跨很多 TCP 段的邮件，正文会被拆到
 * 多帧，每帧只能还原自己那一段——这是抓包还原的固有限制，不做流级拼接，以免把
 * 不同邮件的片段粘在一起。附件也只报大小，不导出二进制。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
public final class EmailMessageDecoder {

    /**
     * 正文预览的最大字符数：还原结果要落库并随报文列表返回，不能无限长
     */
    private static final int MAX_PREVIEW_CHARS = 4000;

    /**
     * 头部最多解析的行数：畸形报文可能整段都长得像头，这里给个上界
     */
    private static final int MAX_HEADER_LINES = 200;

    /**
     * 判断「像邮件头」的最少命中数
     */
    private static final int MIN_HEADER_HITS = 1;

    /**
     * 视为邮件标识的头名字（出现任意一个即可认为这是邮件报文）
     */
    private static final List<String> MESSAGE_MARKERS = List.of(
            "subject", "from", "to", "cc", "date", "mime-version",
            "content-type", "content-transfer-encoding", "received", "message-id", "return-path");

    /**
     * RFC 2047 编码字：=?charset?B|Q?payload?=
     */
    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");

    /**
     * Content-Type / Content-Disposition 里的 filename 参数
     */
    private static final Pattern FILENAME = Pattern.compile("(?i)\\b(?:filename|name)\\s*=\\s*\"?([^\";\\r\\n]+)\"?");

    /**
     * boundary 参数
     */
    private static final Pattern BOUNDARY = Pattern.compile("(?i)\\bboundary\\s*=\\s*\"?([^\";\\r\\n]+)\"?");

    /**
     * charset 参数
     */
    private static final Pattern CHARSET = Pattern.compile("(?i)\\bcharset\\s*=\\s*\"?([\\w.:-]+)\"?");

    /**
     * 头部行的合法名字
     */
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$");

    /**
     * 工具类
     */
    private EmailMessageDecoder() {
    }

    /**
     * 解析结果：头、正文、附件清单。
     *
     * @param headers     头名字（小写）→ 已解码的值
     * @param body        空行之后的原始正文
     * @param attachments 附件清单（没有边界或没有附件时为空）
     */
    public record Message(Map<String, String> headers, String body, List<Attachment> attachments) {

        /**
         * 取头值，缺失返回空串。
         *
         * @param name 头名字（大小写不敏感）
         * @return 头值，缺失为空串
         */
        public String header(String name) {
            String value = this.headers.get(name.toLowerCase(Locale.ROOT));
            return value == null ? "" : value;
        }
    }

    /**
     * 附件描述：只报名字、类型与解码后的字节数。
     *
     * @param name        文件名
     * @param contentType 该 part 的 Content-Type
     * @param size        解码后的字节数
     */
    public record Attachment(String name, String contentType, int size) {
    }

    /**
     * 这段文本是否是邮件报文（有头、且头里有邮件标识）。
     *
     * @param text 载荷文本
     * @return true 表示可以按邮件还原
     */
    public static boolean looksLikeMessage(String text) {
        Message message = parse(text);
        if (message == null) {
            return false;
        }
        int hits = 0;
        for (String marker : MESSAGE_MARKERS) {
            if (message.headers().containsKey(marker)) {
                hits++;
            }
        }
        return hits >= MIN_HEADER_HITS;
    }

    /**
     * 拆头与正文；不是邮件报文时返回 {@code null}。
     *
     * @param text 载荷文本
     * @return 解析结果，或 null
     */
    public static Message parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] lines = text.split("\\r?\\n", -1);
        Map<String, String> headers = new LinkedHashMap<>();
        String lastName = null;
        int index = 0;
        for (; index < lines.length && index < MAX_HEADER_LINES; index++) {
            String line = lines[index];
            if (line.isEmpty()) {
                index++;
                break;
            }
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                // 折行：续行属于上一个头
                if (lastName == null) {
                    break;
                }
                headers.put(lastName, headers.get(lastName) + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                break;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name).matches()) {
                break;
            }
            headers.put(name, decodeHeader(line.substring(colon + 1).trim()));
            lastName = name;
        }
        if (headers.isEmpty()) {
            return null;
        }
        String body = index < lines.length
                ? String.join("\n", java.util.Arrays.copyOfRange(lines, index, lines.length))
                : "";
        List<Attachment> attachmentList = attachments(headers, body);
        return new Message(headers, body, attachmentList);
    }

    /**
     * 还原成人能读的摘要：关键头 + 附件清单 + 解码后的正文预览。
     *
     * @param message 解析结果
     * @return 多行文本
     */
    public static String summarize(Message message) {
        if (message == null) {
            return "[Email Message] empty";
        }
        // [P3C A20 豁免]
        StringBuilder sb = new StringBuilder(256);
        sb.append("[Email Message]");
        appendHeader(sb, "Subject", message.header("subject"));
        appendHeader(sb, "From", message.header("from"));
        appendHeader(sb, "To", message.header("to"));
        appendHeader(sb, "Date", message.header("date"));
        appendHeader(sb, "Message-ID", message.header("message-id"));
        String contentType = message.header("content-type");
        if (!contentType.isEmpty()) {
            appendHeader(sb, "Content-Type", contentType);
        }
        String encoding = message.header("content-transfer-encoding");
        if (!encoding.isEmpty()) {
            appendHeader(sb, "Transfer-Encoding", encoding);
        }
        if (!message.attachments().isEmpty()) {
            sb.append("\nAttachments:");
            for (Attachment attachment : message.attachments()) {
                sb.append("\n  - ").append(attachment.name())
                        .append(" (").append(attachment.contentType().isEmpty() ? "unknown" : attachment.contentType())
                        .append(", ").append(attachment.size()).append(" bytes)");
            }
        }
        String text = textPreview(message);
        if (!text.isEmpty()) {
            sb.append("\n---- body ----\n").append(text);
        }
        return sb.toString();
    }

    /**
     * 邮件正文预览：multipart 取第一个非附件 part，单段邮件直接解码整段。
     *
     * <p>不能对 multipart 的整体正文调 {@link #preview}：那会把 boundary、各 part 头
     * 和 base64 原文一起打出来，比不还原还难读。</p>
     *
     * @param message 解析结果
     * @return 可读正文
     */
    private static String textPreview(Message message) {
        String contentType = message.header("content-type");
        String boundary = boundaryOf(contentType);
        if (boundary == null) {
            return preview(contentType, message.header("content-transfer-encoding"), message.body());
        }
        for (String part : splitParts(message.body(), boundary)) {
            Map<String, String> partHeaderMap = partHeaders(part);
            String disposition = partHeaderMap.getOrDefault("content-disposition", "");
            String partType = partHeaderMap.getOrDefault("content-type", "");
            String name = filenameOf(disposition);
            if (name == null) {
                name = filenameOf(partType);
            }
            if (name != null || disposition.toLowerCase(Locale.ROOT).startsWith("attachment")) {
                continue;
            }
            return preview(partType, partHeaderMap.getOrDefault("content-transfer-encoding", ""), partBody(part));
        }
        return "";
    }

    /**
     * 按 RFC 2047 还原编码字；不是编码字时原样返回。
     *
     * @param value 头值
     * @return 还原后的值
     */
    public static String decodeHeader(String value) {
        if (value == null || value.isEmpty() || value.indexOf("=?") < 0) {
            return value == null ? "" : value;
        }
        // RFC 2047：相邻编码字之间的空白要丢掉，否则 "=?..?= =?..?=" 会多出一个空格
        String normalized = value.replaceAll("\\?=\\s+=\\?", "?==?");
        Matcher matcher = ENCODED_WORD.matcher(normalized);
        StringBuilder sb = new StringBuilder(normalized.length());
        int last = 0;
        while (matcher.find()) {
            sb.append(normalized, last, matcher.start());
            sb.append(decodeWord(matcher.group(1), matcher.group(2), matcher.group(3)));
            last = matcher.end();
        }
        sb.append(normalized.substring(last));
        return sb.toString();
    }

    /**
     * 按 Content-Transfer-Encoding 解码正文。
     *
     * @param body     原始正文
     * @param encoding Content-Transfer-Encoding 的值（可空）
     * @return 解码后的字节
     */
    public static byte[] decodeBody(String body, String encoding) {
        if (body == null || body.isEmpty()) {
            return new byte[0];
        }
        String enc = encoding == null ? "" : encoding.trim().toLowerCase(Locale.ROOT);
        if (enc.contains("base64")) {
            try {
                return Base64.getMimeDecoder().decode(body);
            } catch (IllegalArgumentException e) {
                return body.getBytes(StandardCharsets.UTF_8);
            }
        }
        if (enc.contains("quoted-printable")) {
            return decodeQuotedPrintable(body);
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * quoted-printable 解码：软换行（{@code =\r\n}）丢弃，{@code =XX} 还原成字节。
     *
     * @param body 正文
     * @return 解码后的字节
     */
    public static byte[] decodeQuotedPrintable(String body) {
        if (body == null || body.isEmpty()) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(body.length());
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch != '=') {
                out.write(ch & 0xff);
                continue;
            }
            if (i + 1 < body.length() && (body.charAt(i + 1) == '\r' || body.charAt(i + 1) == '\n')) {
                // 软换行：跳过 = 与紧随的 CRLF
                if (body.charAt(i + 1) == '\r' && i + 2 < body.length() && body.charAt(i + 2) == '\n') {
                    i += 2;
                } else {
                    i += 1;
                }
                continue;
            }
            if (i + 2 < body.length()) {
                // [P3C A20 豁免]
                int hi = Character.digit(body.charAt(i + 1), 16);
                // [P3C A20 豁免]
                int lo = Character.digit(body.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(ch & 0xff);
        }
        return out.toByteArray();
    }

    /**
     * 正文预览：先按传输编码解码，再按 charset 转文本，最后截断。
     *
     * @param contentType 正文的 Content-Type
     * @param encoding    Content-Transfer-Encoding
     * @param body        原始正文
     * @return 可读文本
     */
    public static String preview(String contentType, String encoding, String body) {
        byte[] bytes = decodeBody(body, encoding);
        if (bytes.length == 0) {
            return "";
        }
        String text = new String(bytes, charsetOf(contentType));
        if (text.length() > MAX_PREVIEW_CHARS) {
            text = text.substring(0, MAX_PREVIEW_CHARS) + "\n…（正文已截断）";
        }
        return text;
    }

    /**
     * 从 Content-Type 里取 charset，缺省 UTF-8。
     *
     * @param contentType Content-Type
     * @return 字符集
     */
    public static Charset charsetOf(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 追加一行头（值为空时跳过）。
     *
     * @param sb    输出
     * @param name  头名
     * @param value 头值
     */
    private static void appendHeader(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("\n").append(name).append(": ").append(value.trim());
        }
    }

    /**
     * 还原一个 RFC 2047 编码字。
     *
     * @param charset 字符集
     * @param mode    B（base64）或 Q（quoted-printable 变体）
     * @param payload 编码内容
     * @return 解码文本
     */
    private static String decodeWord(String charset, String mode, String payload) {
        byte[] bytes;
        try {
            if ("B".equalsIgnoreCase(mode)) {
                bytes = Base64.getDecoder().decode(payload);
            } else {
                bytes = decodeQuotedPrintable(payload.replace('_', ' '));
            }
        } catch (IllegalArgumentException e) {
            return "=?" + charset + "?" + mode + "?" + payload + "?=";
        }
        Charset cs;
        try {
            cs = Charset.forName(charset);
        } catch (RuntimeException e) {
            cs = StandardCharsets.UTF_8;
        }
        return new String(bytes, cs);
    }

    /**
     * 从 multipart 正文里找出附件。
     *
     * @param headers 邮件头
     * @param body    正文
     * @return 附件清单
     */
    private static List<Attachment> attachments(Map<String, String> headers, String body) {
        String contentType = headers.get("content-type");
        String boundary = boundaryOf(contentType);
        if (boundary == null || body.isEmpty()) {
            return List.of();
        }
        List<Attachment> out = new ArrayList<>();
        for (String part : splitParts(body, boundary)) {
            Map<String, String> partHeaderMap = partHeaders(part);
            String disposition = partHeaderMap.getOrDefault("content-disposition", "");
            String partType = partHeaderMap.getOrDefault("content-type", "");
            String name = filenameOf(disposition);
            if (name == null) {
                name = filenameOf(partType);
            }
            boolean attachment = name != null || disposition.toLowerCase(Locale.ROOT).startsWith("attachment");
            if (!attachment) {
                continue;
            }
            String enc = partHeaderMap.getOrDefault("content-transfer-encoding", "");
            int size = decodeBody(partBody(part), enc).length;
            // 只保留媒体类型，参数（name=...）已经在文件名里体现过了
            int semicolon = partType.indexOf(';');
            String mediaType = semicolon > 0 ? partType.substring(0, semicolon).trim() : partType.trim();
            out.add(new Attachment(name == null ? "(unnamed)" : name, mediaType, size));
        }
        return out;
    }

    /**
     * 取 boundary 参数。
     *
     * @param contentType Content-Type
     * @return boundary，缺失返回 null
     */
    private static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher matcher = BOUNDARY.matcher(contentType);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * 按 boundary 切 part（首尾的分隔线不返回）。
     *
     * @param body     正文
     * @param boundary boundary
     * @return 各 part 的原文
     */
    private static List<String> splitParts(String body, String boundary) {
        List<String> parts = new ArrayList<>();
        String delimiter = "--" + boundary;
        int cursor = body.indexOf(delimiter);
        while (cursor >= 0) {
            int next = body.indexOf(delimiter, cursor + delimiter.length());
            if (next < 0) {
                break;
            }
            String chunk = body.substring(cursor + delimiter.length(), next);
            cursor = next;
            if (chunk.startsWith("--")) {
                break;
            }
            if (chunk.startsWith("\r\n")) {
                chunk = chunk.substring(2);
            } else if (chunk.startsWith("\n")) {
                chunk = chunk.substring(1);
            }
            if (chunk.endsWith("\r\n")) {
                chunk = chunk.substring(0, chunk.length() - 2);
            } else if (chunk.endsWith("\n")) {
                chunk = chunk.substring(0, chunk.length() - 1);
            }
            parts.add(chunk);
        }
        return parts;
    }

    /**
     * 解析 part 的头。
     *
     * @param part part 原文
     * @return 头名字（小写）→ 值
     */
    private static Map<String, String> partHeaders(String part) {
        Map<String, String> headers = new LinkedHashMap<>();
        String lastName = null;
        for (String line : part.split("\\r?\\n", -1)) {
            if (line.isEmpty()) {
                break;
            }
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && lastName != null) {
                headers.put(lastName, headers.get(lastName) + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                break;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name).matches()) {
                break;
            }
            headers.put(name, decodeHeader(line.substring(colon + 1).trim()));
            lastName = name;
        }
        return headers;
    }

    /**
     * 取 part 的正文（空行之后）。
     *
     * @param part part 原文
     * @return 正文
     */
    private static String partBody(String part) {
        int index = part.indexOf("\r\n\r\n");
        int skip = 4;
        if (index < 0) {
            index = part.indexOf("\n\n");
            skip = 2;
        }
        return index < 0 ? "" : part.substring(index + skip);
    }

    /**
     * 取 filename（或 name）参数。
     *
     * @param value Content-Disposition / Content-Type 的值
     * @return 文件名，缺失返回 null
     */
    private static String filenameOf(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Matcher matcher = FILENAME.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
