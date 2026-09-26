package com.chua.network.support.tshark.restorer;

/**
 * Telnet 协议还原器。
 *
 * <p>Telnet 协议 IAC 命令还原，处理 0xFF 转义序列。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TelnetProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * IAC 转义字节
     */
    private static final int IAC = 0xff;

    /**
     * IAC 命令码下界（0xF0=SE，上界为 0xFF=IAC 自身的转义）
     */
    private static final int MIN_COMMAND = 0xf0;

    /**
     * 正文可打印字节占比下限，低于该值视为二进制协议而非 telnet 文本
     */
    private static final double MIN_PRINTABLE_RATIO = 0.8;

    @Override
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "telnet";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 60;
    }

    @Override
    /**
     * 是否可以Restore
    */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 3) {
            return false;
        }
        return hasIacCommand(rawData) && isMostlyText(rawData);
    }

    /**
     * 是否存在完整的 IAC 命令：0xFF 后紧跟 0xF0~0xFF 的命令码。
     *
     * @param rawData 原始载荷
     * @return true 表示含 telnet 命令
     */
    private static boolean hasIacCommand(byte[] rawData) {
        for (int i = 0; i + 1 < rawData.length; i++) {
            if ((rawData[i] & 0xff) == IAC && (rawData[i + 1] & 0xff) >= MIN_COMMAND) {
                return true;
            }
        }
        return false;
    }

    /**
     * 扣除 IAC 命令后的正文是否以可打印字符为主。
     *
     * <p>纯 IAC 协商包没有正文字节，同样视为 telnet。</p>
     *
     * @param rawData 原始载荷
     * @return true 表示正文可打印比例达标
     */
    private static boolean isMostlyText(byte[] rawData) {
        int printable = 0;
        int total = 0;
        int idx = 0;
        while (idx < rawData.length) {
            int b = rawData[idx] & 0xff;
            if (b == IAC && idx + 1 < rawData.length && (rawData[idx + 1] & 0xff) >= MIN_COMMAND) {
                // IAC + 命令码 [+ 选项码]，命令长度为 2 或 3
                idx += (rawData[idx + 1] & 0xff) == IAC ? 2 : 3;
                continue;
            }
            total++;
            if (b >= 0x20 && b < 0x7f) {
                printable++;
            }
            idx++;
        }
        return total == 0 || printable >= Math.ceil(total * MIN_PRINTABLE_RATIO);
    }

    @Override
    /**
     * Restore
    */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[Telnet] empty";
        }
        StringBuilder sb = new StringBuilder("[Telnet] ");
        int idx = 0;
        int textStart = -1;
        while (idx < rawData.length) {
            int b = rawData[idx] & 0xff;
            if (b == 0xff && idx + 2 < rawData.length) {
                if (textStart >= 0) {
                    int textEnd = idx;
                    if (textEnd > textStart) {
                        sb.append(toText(java.util.Arrays.copyOfRange(rawData, textStart, textEnd))).append(' ');
                    }
                    textStart = -1;
                }
                int cmd = rawData[idx + 1] & 0xff;
                int option = rawData[idx + 2] & 0xff;
                sb.append("[IAC=").append(toTelnetCommand(cmd));
                if (cmd >= 0xfa) {
                    sb.append(' ').append(toTelnetOption(option));
                }
                sb.append("] ");
                idx += 3;
            } else {
                if (textStart < 0) {
                    textStart = idx;
                }
                idx++;
            }
        }
        if (textStart >= 0 && textStart < rawData.length) {
            sb.append(toText(java.util.Arrays.copyOfRange(rawData, textStart, rawData.length)));
        }
        return sb.toString().trim();
    }

    /**
     * 转为telnet命令
     *
     * @param cmd CMD
     * @return 转为telnet命令的结果
     */
    private static String toTelnetCommand(int cmd) {
        return switch (cmd) {
            case 0xfb -> "WILL";
            case 0xfc -> "WONT";
            case 0xfd -> "DO";
            case 0xfe -> "DONT";
            case 0xf0 -> "SE";
            case 0xf1 -> "NOP";
            case 0xf2 -> "DM";
            case 0xf3 -> "BRK";
            case 0xf4 -> "IP";
            case 0xf5 -> "AO";
            case 0xf6 -> "AYT";
            case 0xf7 -> "EC";
            case 0xf8 -> "EL";
            case 0xf9 -> "GA";
            default -> "0x" + Integer.toHexString(cmd);
        };
    }

    /**
     * 转为telnet期权
     *
     * @param option 期权
     * @return 转为telnet期权的结果
     */
    private static String toTelnetOption(int option) {
        return switch (option) {
            case 0x00 -> "TRANSMIT-BINARY";
            case 0x01 -> "ECHO";
            case 0x03 -> "SUPPRESS-GO-AHEAD";
            case 0x05 -> "STATUS";
            case 0x06 -> "TIMING-MARK";
            case 0x18 -> "TERMINAL-TYPE";
            case 0x19 -> "END-OF-RECORD";
            case 0x1f -> "WINDOW-SIZE";
            case 0x20 -> "TERMINAL-SPEED";
            case 0x21 -> "REMOTE-FLOW-CONTROL";
            default -> "0x" + Integer.toHexString(option);
        };
    }
}
