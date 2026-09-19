package com.chua.network.support.tshark.restorer;

/**
 * SMTP 协议还原器。
 *
 * <p>识别 SMTP 命令：HELO/EHLO/MAIL FROM/RCPT TO/DATA/QUIT 等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SmtpProtocolRestorer extends EmailProtocolRestorer {

    @Override
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "smtp";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 105;
    }

    @Override
    /**
     * 是否可以Restore
    */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (!super.canRestore(protocolInfo, rawData)) {
            return false;
        }
        String text = utf8(rawData);
        return text.startsWith("HELO ") || text.startsWith("EHLO ")
                || text.startsWith("MAIL FROM:") || text.startsWith("RCPT TO:")
                || text.startsWith("DATA") || text.startsWith("QUIT")
                || text.startsWith("220 ") || text.startsWith("250 ") || text.startsWith("354 ");
    }

    @Override
    /**
     * Restore
    */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        String text = utf8(rawData).trim();
        if (text.startsWith("220 ") || text.startsWith("250 ") || text.startsWith("354 ")) {
            return "[SMTP Response] " + text;
        }
        if (text.startsWith("EHLO ") || text.startsWith("HELO ")) {
            return "[SMTP Greeting] " + text;
        }
        if (text.startsWith("MAIL FROM:")) {
            return "[SMTP Mail] " + text;
        }
        if (text.startsWith("RCPT TO:")) {
            return "[SMTP Rcpt] " + text;
        }
        return "[SMTP Command] " + text;
    }
}