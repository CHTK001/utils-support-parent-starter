package com.chua.network.support.tshark.restorer;

/**
 * MQTT 控制包协议还原器。
 *
 * <p>MQTT v3.1.1 / v5 固定头部：byte 0 = (PacketType << 4 | Flags)。
 * 数据包类型：1=连接, 2=CONNACK, 3=发布, 4=PUBACK, 5=PUBREC, 6=PUBREL,
 * 7=PUBCOMP, 8=订阅, 9=SUBACK, 10=UNSUBSCRIBE, 11=UNSUBACK, 12=PINGREQ,
 * 13=PINGRESP, 14=断开连接, 15=认证。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MqttProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * 报文类型：CONNECT
     */
    private static final int TYPE_CONNECT = 1;

    /**
     * 报文类型：PUBLISH
     */
    private static final int TYPE_PUBLISH = 3;

    /**
     * 报文类型：PUBREL
     */
    private static final int TYPE_PUBREL = 6;

    /**
     * 报文类型：SUBSCRIBE
     */
    private static final int TYPE_SUBSCRIBE = 8;

    /**
     * 报文类型：UNSUBSCRIBE
     */
    private static final int TYPE_UNSUBSCRIBE = 10;

    /**
     * 报文类型：PINGREQ
     */
    private static final int TYPE_PINGREQ = 12;

    /**
     * 报文类型：PINGRESP
     */
    private static final int TYPE_PINGRESP = 13;

    /**
     * 报文类型：DISCONNECT
     */
    private static final int TYPE_DISCONNECT = 14;

    /**
     * 报文类型：AUTH
     */
    private static final int TYPE_AUTH = 15;

    /**
     * 报文类型下界
     */
    private static final int MIN_TYPE = 1;

    /**
     * 报文类型上界
     */
    private static final int MAX_TYPE = 15;

    /**
     * 剩余长度变长编码的最大字节数
     */
    private static final int MAX_LENGTH_BYTES = 4;

    /**
     * 正文预览的最大字符数
     */
    private static final int MAX_PREVIEW_CHARS = 128;

    @Override
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "mqtt";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 160;
    }

    @Override
    /**
     * 是否可以Restore
    */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 2) {
            return false;
        }
        if (contains(protocolInfo, getProtocolName())) {
            return true;
        }
        int firstByte = rawData[0] & 0xff;
        int packetType = (firstByte >> 4) & 0x0f;
        if (packetType < MIN_TYPE || packetType > MAX_TYPE || !isValidFlags(packetType, firstByte & 0x0f)) {
            return false;
        }
        int[] parsed = readRemainingLength(rawData, 1);
        if (parsed == null) {
            return false;
        }
        int remaining = parsed[0];
        int headerSize = parsed[1];
        if (remaining == 0) {
            return packetType == TYPE_PINGREQ || packetType == TYPE_PINGRESP || packetType == TYPE_DISCONNECT;
        }
        if (headerSize + remaining > rawData.length) {
            return false;
        }
        return packetType != TYPE_CONNECT || isConnectPacket(rawData, headerSize);
    }

    /**
     * 校验固定头后 4 位标志是否符合 MQTT 3.1.1。
     *
     * @param packetType 报文类型
     * @param flags      固定头后 4 位
     * @return true 表示标志合法
     */
    private static boolean isValidFlags(int packetType, int flags) {
        return switch (packetType) {
            case TYPE_PUBREL, TYPE_SUBSCRIBE, TYPE_UNSUBSCRIBE -> flags == 0x02;
            case TYPE_PUBLISH -> ((flags >> 1) & 0x03) != 0x03;
            case TYPE_AUTH -> true;
            default -> flags == 0;
        };
    }

    /**
     * 判断 CONNECT 报文的协议名字段是否为 MQTT/MQIsdp。
     *
     * @param rawData    原始载荷
     * @param headerSize 固定头长度（首字节 + 剩余长度编码字节数）
     * @return true 表示是 MQTT 连接报文
     */
    private static boolean isConnectPacket(byte[] rawData, int headerSize) {
        if (headerSize + 2 > rawData.length) {
            return false;
        }
        int nameLength = ((rawData[headerSize] & 0xff) << 8) | (rawData[headerSize + 1] & 0xff);
        if (nameLength < 4 || nameLength > 6 || headerSize + 2 + nameLength > rawData.length) {
            return false;
        }
        String name = utf8(java.util.Arrays.copyOfRange(rawData, headerSize + 2, headerSize + 2 + nameLength));
        return "MQTT".equals(name) || "MQIsdp".equals(name);
    }

    /**
     * 解析剩余长度变长编码。
     *
     * @param rawData 原始载荷
     * @param offset  编码起始下标
     * @return {@code {剩余长度, 编码结束下标}}，编码非法（超过 4 字节或未闭合）返回 {@code null}
     */
    private static int[] readRemainingLength(byte[] rawData, int offset) {
        int value = 0;
        int multiplier = 1;
        int idx = offset;
        for (int i = 0; i < MAX_LENGTH_BYTES && idx < rawData.length; i++, idx++) {
            int byteVal = rawData[idx] & 0xff;
            value += (byteVal & 0x7f) * multiplier;
            multiplier *= 128;
            if ((byteVal & 0x80) == 0) {
                return new int[]{value, idx + 1};
            }
        }
        return null;
    }

    @Override
    /**
     * Restore
    */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 2) {
            return "[MQTT] empty";
        }
        int firstByte = rawData[0] & 0xff;
        int packetType = (firstByte >> 4) & 0x0f;
        int flags = firstByte & 0x0f;

        StringBuilder sb = new StringBuilder("[MQTT] ");
        sb.append("type=").append(packetType).append(" (").append(toPacketTypeName(packetType)).append(')');
        sb.append(", flags=").append(String.format("0x%x", flags));

        // 解析剩余长度
        int[] parsed = readRemainingLength(rawData, 1);
        int remainingLength = parsed == null ? 0 : parsed[0];
        int idx = parsed == null ? rawData.length : parsed[1];
        sb.append(", remaining=").append(remainingLength).append("B");

        // 解析变长报头 (Protocol Name for CONNECT)
        if (packetType == TYPE_CONNECT && idx + 6 < rawData.length) {
            int protoNameLen = ((rawData[idx] & 0xff) << 8) | (rawData[idx + 1] & 0xff);
            if (protoNameLen >= 4 && protoNameLen <= 6 && idx + 2 + protoNameLen < rawData.length) {
                String protoName = utf8(java.util.Arrays.copyOfRange(rawData, idx + 2, idx + 2 + protoNameLen));
                sb.append(", protocol=").append(protoName);
                int protoLevelIdx = idx + 2 + protoNameLen;
                if (protoLevelIdx < rawData.length) {
                    int level = rawData[protoLevelIdx] & 0xff;
                    sb.append(" v").append(level);
                }
            }
        } else if (packetType == TYPE_PUBLISH && idx + 2 < rawData.length) {
            int qos = (flags >> 1) & 0x03;
            sb.append(", qos=").append(qos);
            if ((flags & 0x08) != 0) {
                sb.append(", dup");
            }
            if ((flags & 0x01) != 0) {
                sb.append(", retain");
            }
            int topicLen = ((rawData[idx] & 0xff) << 8) | (rawData[idx + 1] & 0xff);
            if (topicLen > 0 && idx + 2 + topicLen <= rawData.length) {
                String topic = utf8(java.util.Arrays.copyOfRange(rawData, idx + 2, idx + 2 + topicLen));
                sb.append(", topic=").append(topic);
                int bodyIdx = idx + 2 + topicLen + (qos > 0 ? 2 : 0);
                if (bodyIdx < rawData.length) {
                    sb.append(", payload=").append(preview(utf8(java.util.Arrays.copyOfRange(rawData, bodyIdx, rawData.length))));
                }
            }
        } else if (packetType == TYPE_SUBSCRIBE && idx + 2 < rawData.length) {
            int packetId = ((rawData[idx] & 0xff) << 8) | (rawData[idx + 1] & 0xff);
            sb.append(", packetId=").append(packetId);
            int topicIdx = idx + 2;
            while (topicIdx + 2 < rawData.length) {
                int tLen = ((rawData[topicIdx] & 0xff) << 8) | (rawData[topicIdx + 1] & 0xff);
                if (tLen <= 0 || topicIdx + 2 + tLen > rawData.length) {
                    break;
                }
                sb.append(", topic=").append(utf8(java.util.Arrays.copyOfRange(rawData, topicIdx + 2, topicIdx + 2 + tLen)));
                topicIdx += 2 + tLen;
            }
        }
        return sb.toString();
    }

    /**
     * 截断过长的正文，避免单个还原结果撑爆日志。
     *
     * @param text 正文
     * @return 截断后的正文
     */
    private static String preview(String text) {
        if (text.length() <= MAX_PREVIEW_CHARS) {
            return text;
        }
        return text.substring(0, MAX_PREVIEW_CHARS) + "...";
    }

    /**
     * 转为数据包类型名称
     *
     * @param type 类型
     * @return 转为数据包类型名称的结果
     */
    private static String toPacketTypeName(int type) {
        return switch (type) {
            case 1 -> "CONNECT";
            case 2 -> "CONNACK";
            case 3 -> "PUBLISH";
            case 4 -> "PUBACK";
            case 5 -> "PUBREC";
            case 6 -> "PUBREL";
            case 7 -> "PUBCOMP";
            case 8 -> "SUBSCRIBE";
            case 9 -> "SUBACK";
            case 10 -> "UNSUBSCRIBE";
            case 11 -> "UNSUBACK";
            case 12 -> "PINGREQ";
            case 13 -> "PINGRESP";
            case 14 -> "DISCONNECT";
            case 15 -> "AUTH";
            default -> "Unknown";
        };
    }
}
