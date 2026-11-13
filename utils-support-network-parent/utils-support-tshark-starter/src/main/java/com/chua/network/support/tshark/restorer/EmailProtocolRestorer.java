package com.chua.network.support.tshark.restorer;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 邮件类协议还原器：SMTP / POP3 / IMAP 的命令、响应与 MIME 报文。
 *
 * <p><b>为什么不再只认首行关键字</b>：旧实现要求载荷以 {@code HELO } / {@code 220 } /
 * {@code A01 } 这类固定串开头，于是三类真实流量都还原不出来：</p>
 * <ol>
 *   <li>SMTP 的 {@code DATA} 正文——那几帧是邮件头（Subject / MIME-Version / Content-Type），
 *       不是命令，旧判定直接放过；</li>
 *   <li>IMAP 的标签是客户端自定的（{@code 4 login ...}、{@code c1 SELECT ...}），
 *       写死 A01/A02/A03 命中不了；</li>
 *   <li>POP3 的 {@code RETR} 之后跟的是 MIME 正文，同样不是命令。</li>
 * </ol>
 *
 * <p>现在两层判定：先看是不是一封邮件的头+正文（交给 {@link EmailMessageDecoder} 解码
 * MIME、RFC 2047 与附件），不是的话再按 SMTP 命令/应答、POP3 命令/应答、IMAP 标签行
 * 的语法归类。判定只看行首结构，不做关键字白名单式的穷举，减少「换个客户端就失效」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EmailProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * SMTP 命令（DATA 之后是邮件正文，单独由 MIME 分支处理）
     */
    protected static final Pattern SMTP_COMMAND = Pattern.compile(
            "(?i)^(HELO|EHLO|MAIL\\s+FROM\\s*:|RCPT\\s+TO\\s*:|DATA\\b|QUIT\\b|RSET\\b|"
                    + "VRFY\\b|EXPN\\b|HELP\\b|NOOP\\b|STARTTLS\\b|AUTH\\b)");

    /**
     * SMTP 应答：三位状态码 + 空格或连字符
     */
    protected static final Pattern SMTP_REPLY = Pattern.compile("^[1-5]\\d\\d[ -]");

    /**
     * POP3 命令
     */
    private static final Pattern POP3_COMMAND = Pattern.compile(
            "(?i)^(USER|PASS|APOP|STAT|LIST|UIDL|RETR|TOP|DELE|NOOP|RSET|QUIT|CAPA)\\b");

    /**
     * POP3 应答
     */
    private static final Pattern POP3_REPLY = Pattern.compile("^(\\+OK|-ERR)");

    /**
     * IMAP：客户端标签 + 命令，标签长度 1~12；也接受服务端的 * / + 行
     */
    private static final Pattern IMAP_LINE = Pattern.compile(
            "(?i)^(\\*|\\+|(?![0-9]+$)[A-Za-z0-9]{1,12}\\s+"
                    + "(CAPABILITY|NOOP|LOGOUT|STARTTLS|AUTHENTICATE|LOGIN|SELECT|EXAMINE|CREATE|DELETE|"
                    + "RENAME|SUBSCRIBE|UNSUBSCRIBE|LIST|LSUB|STATUS|APPEND|CHECK|CLOSE|EXPUNGE|SEARCH|"
                    + "FETCH|STORE|COPY|UID|IDLE|NAMESPACE|ENABLE|ID))");

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "email";
    }

    /**
     * 获取Priority
     */
    @Override
    public int getPriority() {
        // [P3C A20 豁免]
        return 110;
    }

    /**
     * 是否可以Restore
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return false;
        }
        String text = utf8(rawData);
        return EmailMessageDecoder.looksLikeMessage(text) || matchesControlLine(text);
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[Email] empty";
        }
        String text = utf8(rawData).trim();
        if (EmailMessageDecoder.looksLikeMessage(text)) {
            return EmailMessageDecoder.summarize(EmailMessageDecoder.parse(text));
        }
        return classify(text);
    }

    /**
     * 载荷首行是否是 SMTP / POP3 / IMAP 的控制行。
     *
     * @param text 载荷文本
     * @return true 表示可按邮件命令/应答还原
     */
    protected static boolean matchesControlLine(String text) {
        String first = firstLine(text);
        if (first.isEmpty()) {
            return false;
        }
        return SMTP_COMMAND.matcher(first).find()
                || SMTP_REPLY.matcher(first).find()
                || POP3_COMMAND.matcher(first).find()
                || POP3_REPLY.matcher(first).find()
                || IMAP_LINE.matcher(first).find();
    }

    /**
     * 控制行归类成可读前缀。
     *
     * @param text 载荷文本
     * @return 带前缀的文本
     */
    protected static String classify(String text) {
        String first = firstLine(text);
        if (POP3_REPLY.matcher(first).find()) {
            return "[POP3 Response] " + text;
        }
        if (POP3_COMMAND.matcher(first).find()) {
            return "[POP3 Command] " + text;
        }
        if (IMAP_LINE.matcher(first).find()) {
            return "[IMAP] " + text;
        }
        if (SMTP_REPLY.matcher(first).find()) {
            return "[SMTP Response] " + text;
        }
        return "[SMTP Command] " + text;
    }

    /**
     * 取首行（去掉行尾 CR）。
     *
     * @param text 文本
     * @return 首行
     */
    protected static String firstLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int index = text.indexOf('\n');
        String line = index < 0 ? text : text.substring(0, index);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
