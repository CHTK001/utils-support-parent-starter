package com.chua.network.support.tshark.restorer;

import java.util.Locale;
import java.util.Map;

/**
 * SMTP 协议还原器。
 *
 * <p>相对 {@link EmailProtocolRestorer} 只做两件事：把协议名钉成 {@code smtp}
 * （这样 tshark 解出 {@code smtp} 层时优先命中本类，优先级 105 早于 email 的 110），
 * 以及给命令/应答加上更具体的前缀（Greeting / Mail / Rcpt）。</p>
 *
 * <p><b>关键修复</b>：{@code DATA} 之后的帧载荷是一封邮件的头与正文，旧实现用
 * 「必须以 HELO/MAIL FROM/220 开头」把它挡在外面，于是邮件内容永远还原不出来。
 * 现在先按 MIME 报文处理，识别不到邮件头才回落到命令/应答。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SmtpProtocolRestorer extends EmailProtocolRestorer {

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "smtp";
    }

    /**
     * 获取Priority
     */
    @Override
    public int getPriority() {
        return 105;
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
        if (EmailMessageDecoder.looksLikeMessage(text)) {
            return true;
        }
        String first = firstLine(text);
        return SMTP_COMMAND.matcher(first).find() || SMTP_REPLY.matcher(first).find();
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[SMTP] empty";
        }
        String text = utf8(rawData).trim();
        if (EmailMessageDecoder.looksLikeMessage(text)) {
            return EmailMessageDecoder.summarize(EmailMessageDecoder.parse(text));
        }
        String first = firstLine(text);
        String upper = first.toUpperCase(Locale.ROOT);
        if (SMTP_REPLY.matcher(first).find()) {
            return "[SMTP Response] " + text;
        }
        if (upper.startsWith("EHLO ") || upper.startsWith("HELO ")) {
            return "[SMTP Greeting] " + text;
        }
        if (upper.startsWith("MAIL FROM:")) {
            return "[SMTP Mail] " + text;
        }
        if (upper.startsWith("RCPT TO:")) {
            return "[SMTP Rcpt] " + text;
        }
        return "[SMTP Command] " + text;
    }
}
