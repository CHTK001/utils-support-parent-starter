// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.network.support.tshark.restorer;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * MSRP 协议还原器（RCS / 5G 消息的正文段）。
 *
 * <p><b>为什么加它</b>：短信不只是运营商信令 —— 手机在 Wi-Fi 下发的 RCS（5G 消息）
 * 走的是 SIP 呼叫 + MSRP 传正文，这一段是**明文可读**的文本协议，抓到了就能看到
 * 收件人、会话 ID、内容类型和正文。没有它，「短信」只覆盖 SMPP 网关那一半。</p>
 *
 * <p>格式：首行 {@code MSRP <transaction-id> <method|status>}，随后是
 * {@code To-Path} / {@code From-Path} / {@code Content-Type} / {@code Content-Length}
 * 等头；空行之后按 {@code Content-Length} 取正文，末尾是
 * {@code -------<transaction-id>$} 结束符。</p>
 *
 * <p><b>边界</b>：MSRP over TLS（RCS 默认 443）是密文，需要 keylog 才能看到内容；
 * 这里只解明文 TCP（2855）或已解密的载荷。多段长消息按 chunk 传输时，单帧只还原
 * 自己那一段。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
public class MsrpProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * 协议前缀
     */
    private static final String PREFIX = "MSRP ";

    /**
     * 正文预览上限
     */
    private static final int MAX_BODY = 400;

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "msrp";
    }

    /**
     * 获取Priority
     */
    @Override
    public int getPriority() {
        return 58;
    }

    /**
     * 是否可以Restore
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < PREFIX.length() + 4) {
            return false;
        }
        String head = new String(rawData, 0, Math.min(rawData.length, 64), StandardCharsets.US_ASCII);
        if (!head.startsWith(PREFIX)) {
            return false;
        }
        // 只看首行：后面还有 To-Path 等头，按空格切会把头也带进来
        String first = firstLine(head);
        String[] parts = first.substring(PREFIX.length()).split(" ");
        if (parts.length < 2) {
            return false;
        }
        String method = parts[1].trim().toUpperCase(Locale.ROOT);
        return "SEND".equals(method) || "REPORT".equals(method)
                || method.matches("[1-5]\\d\\d");
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < PREFIX.length()) {
            return "[MSRP] empty";
        }
        String text = new String(rawData, StandardCharsets.UTF_8);
        int headEnd = text.indexOf("\r\n\r\n");
        int skip = 4;
        if (headEnd < 0) {
            headEnd = text.indexOf("\n\n");
            skip = 2;
        }
        String head = headEnd < 0 ? text : text.substring(0, headEnd);
        String body = headEnd < 0 ? "" : text.substring(headEnd + skip);

        String first = firstLine(head);
        String[] parts = first.substring(PREFIX.length()).split(" ", 3);
        String transaction = parts.length > 0 ? parts[0] : "";
        String method = parts.length > 1 ? parts[1] : "";

        StringBuilder sb = new StringBuilder("[MSRP] ").append(method);
        String to = header(head, "To-Path");
        String from = header(head, "From-Path");
        String type = header(head, "Content-Type");
        String messageId = header(head, "Message-ID");
        if (!to.isEmpty()) {
            sb.append(", to=").append(abbreviate(to));
        }
        if (!from.isEmpty()) {
            sb.append(", from=").append(abbreviate(from));
        }
        if (!type.isEmpty()) {
            sb.append(", type=").append(type);
        }
        if (!messageId.isEmpty()) {
            sb.append(", id=").append(messageId);
        }
        sb.append(", txn=").append(transaction);
        String content = stripTerminator(body, transaction);
        if (!content.isEmpty()) {
            sb.append(", body=").append(content.length() > MAX_BODY
                    ? content.substring(0, MAX_BODY) + "…" : content);
        }
        return sb.toString();
    }

    /**
     * 去正文尾部的 {@code -------<txn>$} 结束符。
     *
     * @param body        正文
     * @param transaction 事务 ID
     * @return 清理后的正文
     */
    private static String stripTerminator(String body, String transaction) {
        String value = body;
        String terminator = "-------" + transaction + "$";
        int at = value.indexOf(terminator);
        if (at >= 0) {
            value = value.substring(0, at);
        }
        return value.trim();
    }

    /**
     * 取首行。
     *
     * @param text 文本
     * @return 首行
     */
    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        String line = index < 0 ? text : text.substring(0, index);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * 取头值（大小写不敏感）。
     *
     * @param head 头部文本
     * @param name 头名
     * @return 值，缺失返回空串
     */
    private static String header(String head, String name) {
        for (String line : head.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    /**
     * 路径只保留主机与端口，避免一行太长。
     *
     * @param path To-Path / From-Path
     * @return 截断后的值
     */
    private static String abbreviate(String path) {
        String value = path;
        int semicolon = value.indexOf(';');
        if (semicolon > 0) {
            value = value.substring(0, semicolon);
        }
        return value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }
}
