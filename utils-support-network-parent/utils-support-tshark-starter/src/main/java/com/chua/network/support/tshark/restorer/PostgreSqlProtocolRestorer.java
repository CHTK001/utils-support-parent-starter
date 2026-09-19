package com.chua.network.support.tshark.restorer;

/**
 * PostgreSQL 协议还原器。
 *
 * <p>PostgreSQL 前端/后端协议：消息格式 = 首字节 type + 4 字节 length + payload。
 * 类型: 'Q'=查询, 'P'=解析, 'B'=Bind, 'E'=执行, 'D'=Describe, 'S'=同步,
 * 'X'=Terminate, 'C'=命令完成, 'T'=rowdescription, 'D'=数据row, 'I'=命令id,
 * 'R'=认证, 'K'=backend键数据, 'Z'=就绪for查询, 'E'=错误响应,
 * 'N'=notice响应, 'S'=参数状态。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PostgreSqlProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "postgresql";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 190;
    }

    @Override
    /**
     * 是否可以Restore
    */
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
    /**
     * Restore
    */
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

    /**
     * 转为类型名称
     *
     * @param type 类型
     * @return 转为类型名称的结果
     */
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
