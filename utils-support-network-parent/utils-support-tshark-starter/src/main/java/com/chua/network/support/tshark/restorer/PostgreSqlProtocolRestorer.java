package com.chua.network.support.tshark.restorer;

/**
 * PostgreSQL 协议还原器。
 *
 * <p>PostgreSQL 前端/后端协议：消息格式 = 首字节 type + 4 字节 length + payload。
 * type: 'Q'=Query, 'P'=Parse, 'B'=Bind, 'E'=Execute, 'D'=Describe, 'S'=Sync,
 * 'X'=Terminate, 'C'=CommandComplete, 'T'=RowDescription, 'D'=DataRow, 'I'=CommandId,
 * 'R'=Authentication, 'K'=BackendKeyData, 'Z'=ReadyForQuery, 'E'=ErrorResponse,
 * 'N'=NoticeResponse, 'S'=ParameterStatus。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PostgreSqlProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    public String getProtocolName() {
        return "postgresql";
    }

    @Override
    public int getPriority() {
        return 190;
    }

    @Override
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 5) {
            return false;
        }
        char type = (char) (rawData[0] & 0xff);
        int length = ((rawData[1] & 0xff) << 24) | ((rawData[2] & 0xff) << 16)
                | ((rawData[3] & 0xff) << 8) | (rawData[4] & 0xff);
        boolean ok = length == rawData.length - 1;
        switch (type) {
            case 'Q': case 'P': case 'B': case 'E': case 'S': case 'X':
            case 'C': case 'T': case 'I': case 'R': case 'K': case 'Z':
            case 'N':
                return ok;
            default:
                return false;
        }
    }

    @Override
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 5) {
            return "[PostgreSQL] empty";
        }
        char type = (char) (rawData[0] & 0xff);
        int length = ((rawData[1] & 0xff) << 24) | ((rawData[2] & 0xff) << 16)
                | ((rawData[3] & 0xff) << 8) | (rawData[4] & 0xff);

        StringBuilder sb = new StringBuilder("[PostgreSQL] ");
        sb.append("type=").append(type).append(" (").append(toTypeName(type)).append(')');
        sb.append(", length=").append(length);
        if (type == 'Q' && rawData.length > 5) {
            String sql = utf8(java.util.Arrays.copyOfRange(rawData, 5, rawData.length));
            if (sql.endsWith("\0")) {
                sql = sql.substring(0, sql.length() - 1);
            }
            if (sql.length() > 256) {
                sql = sql.substring(0, 256) + "...(truncated)";
            }
            sb.append("\n  SQL: ").append(sql);
        } else if (type == 'C' && rawData.length > 5) {
            String cmd = utf8(java.util.Arrays.copyOfRange(rawData, 5, rawData.length));
            if (cmd.endsWith("\0")) {
                cmd = cmd.substring(0, cmd.length() - 1);
            }
            sb.append(", command=").append(cmd);
        } else if (type == 'E' || type == 'N') {
            sb.append(", messageLength=").append(length);
        }
        return sb.toString();
    }

    private static String toTypeName(char type) {
        return switch (type) {
            case 'Q' -> "Query";
            case 'P' -> "Parse";
            case 'B' -> "Bind";
            case 'E' -> "Execute";
            case 'D' -> "Describe";
            case 'S' -> "Sync";
            case 'X' -> "Terminate";
            case 'C' -> "CommandComplete";
            case 'T' -> "RowDescription";
            case 'I' -> "CommandId";
            case 'R' -> "Authentication";
            case 'K' -> "BackendKeyData";
            case 'Z' -> "ReadyForQuery";
            case 'N' -> "NoticeResponse";
            default -> "Unknown(" + type + ")";
        };
    }
}