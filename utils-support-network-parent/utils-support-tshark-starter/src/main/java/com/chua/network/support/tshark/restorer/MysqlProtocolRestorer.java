package com.chua.network.support.tshark.restorer;

/**
 * MySQL 协议还原器。
 *
 * <p>MySQL packet: payload_length(3 LE) + sequence_id(1) + payload。
 * 握手/响应首包含 Server Greeting 标识 "5.5.x-5.7.x" / "8.0.x"。
 * COM_QUERY 命令 packet payload 首字节为 0x03。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    public String getProtocolName() {
        return "mysql";
    }

    @Override
    public int getPriority() {
        return 180;
    }

    @Override
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 5) {
            return false;
        }
        int payloadLen = (rawData[0] & 0xff) | ((rawData[1] & 0xff) << 8) | ((rawData[2] & 0xff) << 16);
        int seqId = rawData[3] & 0xff;
        if (payloadLen != rawData.length - 4) {
            return false;
        }
        if (seqId != 0 && seqId != 1) {
            return false;
        }
        if (seqId == 0 && payloadLen < 30) {
            return false;
        }
        if (seqId == 1 && payloadLen < 1) {
            return false;
        }
        return true;
    }

    @Override
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 5) {
            return "[MySQL] empty";
        }
        int payloadLen = (rawData[0] & 0xff) | ((rawData[1] & 0xff) << 8) | ((rawData[2] & 0xff) << 16);
        int seqId = rawData[3] & 0xff;
        StringBuilder sb = new StringBuilder("[MySQL] ");
        sb.append("seq=").append(seqId);
        sb.append(", payload=").append(payloadLen).append("B");
        if (seqId == 0) {
            // Server Greeting
            int protoVer = rawData[4] & 0xff;
            sb.append(", greeting, serverVersion=").append(parseServerVersion(rawData));
            sb.append(", protocol=").append(protoVer);
        } else {
            int cmd = rawData[4] & 0xff;
            sb.append(", command=").append(String.format("0x%02x", cmd));
            sb.append(" (").append(toCommandName(cmd)).append(')');
            if (cmd == 0x03 && payloadLen > 1) {
                String query = utf8(java.util.Arrays.copyOfRange(rawData, 5, 5 + payloadLen - 1));
                if (query.length() > 256) {
                    query = query.substring(0, 256) + "...(truncated)";
                }
                sb.append("\n  SQL: ").append(query);
            }
        }
        return sb.toString();
    }

    private static String parseServerVersion(byte[] data) {
        if (data.length < 5) {
            return "?";
        }
        int idx = 5;
        StringBuilder sb = new StringBuilder();
        while (idx < data.length && data[idx] != 0) {
            sb.append((char) (data[idx] & 0xff));
            idx++;
        }
        return sb.toString();
    }

    private static String toCommandName(int cmd) {
        return switch (cmd) {
            case 0x00 -> "COM_SLEEP";
            case 0x01 -> "COM_QUIT";
            case 0x02 -> "COM_INIT_DB";
            case 0x03 -> "COM_QUERY";
            case 0x04 -> "COM_FIELD_LIST";
            case 0x05 -> "COM_CREATE_DB";
            case 0x06 -> "COM_DROP_DB";
            case 0x07 -> "COM_REFRESH";
            case 0x08 -> "COM_SHUTDOWN";
            case 0x09 -> "COM_STATISTICS";
            case 0x0a -> "COM_PROCESS_INFO";
            case 0x0e -> "COM_PING";
            case 0x0f -> "COM_RESET_CONNECTION";
            case 0x11 -> "COM_CHANGE_USER";
            case 0x13 -> "COM_SET_OPTION";
            case 0x1b -> "COM_STMT_PREPARE";
            case 0x1c -> "COM_STMT_EXECUTE";
            case 0x1e -> "COM_STMT_CLOSE";
            default -> "COM_0x" + Integer.toHexString(cmd);
        };
    }
}