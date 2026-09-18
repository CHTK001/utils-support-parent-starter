package com.chua.network.support.tshark.restorer;

/**
* Redis RESP 协议还原器。
*
* <p>Redis 序列化协议：
* <ul>
*   <li>*N - 数组 N 个元素</li>
*   <li>$N - 字符串，长度 N</li>
*   <li>:N - 整数 N</li>
*   <li>+OK\r\n - 简单字符串</li>
*   <li>-ERR message\r\n - 错误</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public class RedisProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "redis";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 200;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return false;
        }
        int type = rawData[0] & 0xff;
        return type == '*' || type == '$' || type == ':' || type == '+' || type == '-';
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[Redis] empty";
        }
        int type = rawData[0] & 0xff;
        StringBuilder sb = new StringBuilder("[Redis] ");
        if (type == '*') {
            int arrayLen = parseLength(rawData, 1);
            sb.append("Array(").append(arrayLen).append(')');
        } else if (type == '$') {
            int strLen = parseLength(rawData, 1);
            sb.append("BulkString(").append(strLen).append(')');
            if (strLen > 0 && rawData.length > 4 + strLen) {
                int start = 4;
                int end = start + strLen;
                String content = utf8(java.util.Arrays.copyOfRange(rawData, start, end));
                if (content.length() > 128) {
                    content = content.substring(0, 128) + "...(truncated)";
                }
                sb.append(" = ").append(content);
            }
        } else if (type == ':') {
            sb.append("Integer(").append(parseLong(rawData, 1)).append(')');
        } else if (type == '+') {
            String simple = toText(rawData).trim();
            sb.append("SimpleString = ").append(simple.substring(1));
        } else if (type == '-') {
            String err = toText(rawData).trim();
            sb.append("Error = ").append(err.substring(1));
        }
        return sb.toString();
    }

    /**
    * 解析获取长度
    *
    * @param data 数据
    * @param offset 偏移量
    * @return 解析长度的结果
    */
    private static int parseLength(byte[] data, int offset) {
        int value = 0;
        int idx = offset;
        while (idx < data.length) {
            int b = data[idx] & 0xff;
            if (b == '\r' || b == '\n' || b == ' ') {
                return value;
            }
            if (b < '0' || b > '9') {
                return -1;
            }
            value = value * 10 + (b - '0');
            idx++;
        }
        return value;
    }

    /**
    * 解析Long
    *
    * @param data 数据
    * @param offset 偏移量
    * @return 解析long的结果
    */
    private static long parseLong(byte[] data, int offset) {
        long value = 0;
        boolean negative = false;
        int idx = offset;
        if (idx < data.length && data[idx] == '-') {
            negative = true;
            idx++;
        }
        while (idx < data.length) {
            int b = data[idx] & 0xff;
            if (b < '0' || b > '9') {
                break;
            }
            value = value * 10 + (b - '0');
            idx++;
        }
        return negative ? -value : value;
    }
}
