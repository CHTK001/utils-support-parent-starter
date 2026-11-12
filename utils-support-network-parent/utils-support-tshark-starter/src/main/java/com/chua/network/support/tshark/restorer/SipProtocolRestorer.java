// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.network.support.tshark.restorer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SIP 协议还原器（呼叫信令 + MESSAGE 正文）。
 *
 * <p><b>为什么补 MESSAGE 正文</b>：SMS over IMS / VoWiFi 的短信走 SIP {@code MESSAGE}，
 * 正文就在空行之后。旧实现只取首行，等于「知道有人发短信，但内容看不见」。
 * 现在把 From / To / Call-ID / Content-Type 与正文一起还原，Wi-Fi 下的短信才真的可读。</p>
 *
 * <p>其它方法（INVITE / REGISTER / BYE …）保留首行与主叫被叫，够回答「谁打给谁」。</p>
 *
 * <p><b>边界</b>：SIP over TLS（5061）是密文，需要 keylog；分片的长短信按
 * {@code Content-Length} 截取，跨 TCP 段的部分不在单帧里拼接。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SipProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * SIP 支持的方法（用于首行判定）
     */
    private static final String[] METHODS = {"INVITE ", "REGISTER ", "BYE ", "CANCEL ", "ACK ",
            "OPTIONS ", "PRACK ", "INFO ", "SUBSCRIBE ", "NOTIFY ", "REFER ", "MESSAGE ", "UPDATE "};

    /**
     * 正文预览上限
     */
    private static final int MAX_BODY = 400;

    /**
     * Content-Type 里的 charset
     */
    private static final Pattern CHARSET = Pattern.compile("(?i)\\bcharset\\s*=\\s*\"?([\\w.:-]+)\"?");

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "sip";
    }

    /**
     * 获取Priority
     */
    @Override
    public int getPriority() {
        return 240;
    }

    /**
     * 是否可以Restore
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return false;
        }
        String text = utf8(rawData);
        if (text.startsWith("SIP/2.0")) {
            return true;
        }
        for (String method : METHODS) {
            if (text.startsWith(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[SIP] empty";
        }
        byte[] marker = {0x0d, 0x0a, 0x0d, 0x0a};
        int bodyAt = indexOf(rawData, marker);
        int skip = 4;
        if (bodyAt < 0) {
            marker = new byte[]{0x0a, 0x0a};
            bodyAt = indexOf(rawData, marker);
            skip = 2;
        }
        int headEnd = bodyAt < 0 ? rawData.length : bodyAt;
        String head = new String(rawData, 0, headEnd, StandardCharsets.UTF_8);
        byte[] bodyBytes = bodyAt < 0 ? new byte[0]
                : java.util.Arrays.copyOfRange(rawData, bodyAt + skip, rawData.length);
        String firstLine = firstLine(head);

        StringBuilder sb = new StringBuilder("[SIP] ").append(firstLine);
        String from = header(head, "From");
        String to = header(head, "To");
        String callId = header(head, "Call-ID");
        if (!from.isEmpty()) {
            sb.append(", from=").append(abbreviate(from, 60));
        }
        if (!to.isEmpty()) {
            sb.append(", to=").append(abbreviate(to, 60));
        }
        if (!callId.isEmpty()) {
            sb.append(", call-id=").append(abbreviate(callId, 48));
        }
        if (firstLine.startsWith("MESSAGE ")) {
            String type = header(head, "Content-Type");
            String content = decode(bodyBytes, type);
            if (!content.isEmpty()) {
                sb.append(", type=").append(type.isEmpty() ? "text/plain" : type);
                sb.append(", body=").append(content.length() > MAX_BODY
                        ? content.substring(0, MAX_BODY) + "…" : content);
            }
        }
        return sb.toString();
    }

    /**
     * 按 Content-Type 的 charset 解正文字节。
     *
     * @param body 正文字节
     * @param type Content-Type
     * @return 可读文本
     */
    private static String decode(byte[] body, String type) {
        if (body == null || body.length == 0) {
            return "";
        }
        Charset charset = StandardCharsets.UTF_8;
        Matcher matcher = CHARSET.matcher(type);
        if (matcher.find()) {
            try {
                charset = Charset.forName(matcher.group(1));
            } catch (RuntimeException ignored) {
                charset = StandardCharsets.UTF_8;
            }
        }
        return new String(body, charset).trim();
    }

    /**
     * 在字节数组里找子串。
     *
     * @param data 数据
     * @param key  子串
     * @return 下标，未找到返回 -1
     */
    private static int indexOf(byte[] data, byte[] key) {
        if (data == null || key == null || key.length == 0 || data.length < key.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= data.length - key.length; i++) {
            for (int j = 0; j < key.length; j++) {
                if (data[i + j] != key[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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
     * 取头值（大小写不敏感，支持折行）。
     *
     * @param head 头部文本
     * @param name 头名
     * @return 值，缺失返回空串
     */
    private static String header(String head, String name) {
        String[] lines = head.split("\r?\n");
        StringBuilder value = new StringBuilder();
        boolean collecting = false;
        for (String line : lines) {
            if (!collecting) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                if (!line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    continue;
                }
                value.append(line.substring(colon + 1).trim());
                collecting = true;
                continue;
            }
            if (line.startsWith(" ") || line.startsWith("\t")) {
                value.append(' ').append(line.trim());
                continue;
            }
            break;
        }
        return value.toString();
    }

    /**
     * 截断长值。
     *
     * @param value 原值
     * @param limit 上限
     * @return 截断后的值
     */
    private static String abbreviate(String value, int limit) {
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() > limit ? text.substring(0, limit) + "…" : text;
    }
}
