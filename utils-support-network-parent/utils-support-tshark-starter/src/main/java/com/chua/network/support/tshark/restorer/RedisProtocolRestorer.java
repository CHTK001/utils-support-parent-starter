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
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "redis";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 200;
    }

    @Override
    /**
     * 是否可以Restore
    */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return false;
        }
        if (contains(protocolInfo, getProtocolName())) {
            return true;
        }
        int lineEnd = indexOfLineEnd(rawData);
        if (lineEnd < 2 || (rawData[lineEnd - 1] & 0xff) != '\r') {
            return false;
        }
        int type = rawData[0] & 0xff;
        if (type == '+' || type == '-') {
            return isPrintable(rawData, 1, lineEnd - 1);
        }
        return (type == '*' || type == '$' || type == ':') && isInteger(rawData, 1, lineEnd - 1);
    }

    /**
     * 查找首行换行符下标。
     *
     * @param rawData 原始载荷
     * @return {@code '\n'} 下标，不存在返回 -1
     */
    private static int indexOfLineEnd(byte[] rawData) {
        for (int i = 1; i < rawData.length; i++) {
            if ((rawData[i] & 0xff) == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判断区间内容是否为整数（允许前导负号）。
     *
     * @param rawData 原始载荷
     * @param start   起始下标（含）
     * @param end     结束下标（不含）
     * @return true 表示是整数
     */
    private static boolean isInteger(byte[] rawData, int start, int end) {
        int i = start;
        if (i < end && (rawData[i] & 0xff) == '-') {
            i++;
        }
        if (i >= end) {
            return false;
        }
        for (; i < end; i++) {
            int b = rawData[i] & 0xff;
            if (b < '0' || b > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断区间内容是否全为可打印 ASCII。
     *
     * @param rawData 原始载荷
     * @param start   起始下标（含）
     * @param end     结束下标（不含）
     * @return true 表示可打印
     */
    private static boolean isPrintable(byte[] rawData, int start, int end) {
        if (start >= end) {
            return false;
        }
        for (int i = start; i < end; i++) {
            int b = rawData[i] & 0xff;
            if (b < 0x20 || b > 0x7e) {
                return false;
            }
        }
        return true;
    }

    @Override
    /**
     * Restore
    */
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
