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

    @Override
    /** 获取ProtocolName */
    public String getProtocolName() {
        return "telnet";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 60;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return false;
        }
        for (byte b : rawData) {
            if ((b & 0xff) == 0xff) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** Restore */
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

    /** ToTelnetCommand */
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

    /** ToTelnetOption */
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