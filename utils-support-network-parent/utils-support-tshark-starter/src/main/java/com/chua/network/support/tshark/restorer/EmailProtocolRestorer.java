package com.chua.network.support.tshark.restorer;

/**
 * Email 协议还原器。
 *
 * <p>还原 SMTP / POP3 / IMAP 协议命令与响应，包含典型命令字解析。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EmailProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取ProtocolName */
    public String getProtocolName() {
        return "email";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 110;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return false;
        }
        String text = utf8(rawData);
        if (text.startsWith("220 ") || text.startsWith("EHLO ") || text.startsWith("HELO ")
                || text.startsWith("MAIL FROM:") || text.startsWith("RCPT TO:")
                || text.startsWith("DATA") || text.startsWith("QUIT")
                || text.startsWith("250 ") || text.startsWith("354 ")) {
            return true;
        }
        if (text.startsWith("+OK") || text.startsWith("-ERR") || text.startsWith("USER ")
                || text.startsWith("PASS ") || text.startsWith("LIST") || text.startsWith("STAT")
                || text.startsWith("RETR ") || text.startsWith("TOP ") || text.startsWith("UIDL")) {
            return true;
        }
        if (text.startsWith("A01 ") || text.startsWith("A02 ") || text.startsWith("A03 ")
                || text.startsWith("* OK") || text.startsWith("* LIST")
                || text.startsWith(". LOGIN") || text.startsWith(". SELECT")) {
            return true;
        }
        return false;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[Email] empty";
        }
        String text = utf8(rawData).trim();
        if (text.startsWith("220 ") || text.startsWith("250 ") || text.startsWith("354 ")
                || text.startsWith("+OK") || text.startsWith("-ERR")
                || text.startsWith("* OK") || text.startsWith("* LIST")) {
            return "[Email Response] " + text;
        }
        return "[Email Command] " + text;
    }
}