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

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "mqtt";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 160;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 2) {
            return false;
        }
        int firstByte = rawData[0] & 0xff;
        int packetType = (firstByte >> 4) & 0x0f;
        return packetType >= 1 && packetType <= 15;
    }

    @Override
    /** Restore */
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
        int remainingLength = 0;
        int multiplier = 1;
        int idx = 1;
        int encodedLengthBytes = 0;
        while (idx < rawData.length && encodedLengthBytes < 4) {
            int byteVal = rawData[idx] & 0xff;
            remainingLength += (byteVal & 0x7f) * multiplier;
            multiplier *= 128;
            idx++;
            encodedLengthBytes++;
            if ((byteVal & 0x80) == 0) {
                break;
            }
        }
        sb.append(", remaining=").append(remainingLength).append("B");

        // 解析变长报头 (Protocol Name for CONNECT)
        if (packetType == 1 && idx + 6 < rawData.length) {
            int protoNameLen = ((rawData[idx] & 0xff) << 8) | (rawData[idx + 1] & 0xff);
            if (protoNameLen >= 4 && protoNameLen <= 6) {
                String protoName = utf8(java.util.Arrays.copyOfRange(rawData, idx + 2, idx + 2 + protoNameLen));
                sb.append(", protocol=").append(protoName);
                int protoLevelIdx = idx + 2 + protoNameLen;
                if (protoLevelIdx < rawData.length) {
                    int level = rawData[protoLevelIdx] & 0xff;
                    sb.append(" v").append(level);
                }
            }
        } else if (packetType == 3 && idx + 2 < rawData.length) {
            int topicLen = ((rawData[idx] & 0xff) << 8) | (rawData[idx + 1] & 0xff);
            if (topicLen > 0 && idx + 2 + topicLen <= rawData.length) {
                String topic = utf8(java.util.Arrays.copyOfRange(rawData, idx + 2, idx + 2 + topicLen));
                sb.append(", topic=").append(topic);
            }
        } else if (packetType == 8 && idx + 2 < rawData.length) {
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
