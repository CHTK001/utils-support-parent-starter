package com.chua.network.support.tshark.restorer;

/**
 * FTP 协议还原器。
 *
 * <p>FTP 控制连接命令/响应还原：USER、PASS、RETR、STOR、LIST、CWD 等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FtpProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    public String getProtocolName() {
        return "ftp";
    }

    @Override
    public int getPriority() {
        return 40;
    }

    @Override
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return false;
        }
        String text = utf8(rawData);
        if (text.startsWith("220 ") || text.startsWith("USER ") || text.startsWith("PASS ")) {
            return true;
        }
        String[] cmds = {"CWD", "PWD", "LIST", "RETR", "STOR", "DELE", "MKD",
                "RMD", "TYPE", "PASV", "PORT", "QUIT", "SYST", "FEAT", "NOOP"};
        for (String cmd : cmds) {
            if (text.startsWith(cmd + " ") || text.startsWith(cmd + "\r\n") || text.startsWith(cmd + "\n")) {
                return true;
            }
        }
        return isFtpResponse(text);
    }

    @Override
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[FTP] empty";
        }
        String text = utf8(rawData).trim();
        if (isFtpResponse(text)) {
            return "[FTP Response] " + text;
        }
        if (text.contains(" ")) {
            return "[FTP Command] " + text.split(" ", 2)[0].toUpperCase() + " " + text.substring(text.indexOf(' ') + 1);
        }
        return "[FTP Command] " + text;
    }

    /**
     * 判断是否为 FTP 响应（3 位数字 + 空格 + 文本）。
     */
    private static boolean isFtpResponse(String text) {
        return text.length() >= 4
                && Character.isDigit(text.charAt(0))
                && Character.isDigit(text.charAt(1))
                && Character.isDigit(text.charAt(2))
                && text.charAt(3) == ' ';
    }
}