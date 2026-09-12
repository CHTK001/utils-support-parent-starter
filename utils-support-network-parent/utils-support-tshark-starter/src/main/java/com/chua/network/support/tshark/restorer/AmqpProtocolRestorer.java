package com.chua.network.support.tshark.restorer;

/**
* AMQP 0-9-1 协议还原器。
*
* <p>AMQP 帧结构：type(1) + channel(2) + size(可变) + payload。
* 类型: 0x01=方法, 0x02=头部, 0x03=主体, 0x08=CONTROL。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class AmqpProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "amqp";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 170;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 7) {
            return false;
        }
        int type = rawData[0] & 0xff;
        if (type != 0x01 && type != 0x02 && type != 0x03 && type != 0x08) {
            return false;
        }
        // 0-9-1 magic protocol header: "AMQP"
        if (rawData.length >= 8
                && rawData[0] == 'A' && rawData[1] == 'M' && rawData[2] == 'Q' && rawData[3] == 'P') {
            return rawData[7] == 0x01;
        }
        return true;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 7) {
            return "[AMQP] empty";
        }
 // 协议 头部
        if (rawData.length >= 8 && rawData[0] == 'A' && rawData[1] == 'M'
                && rawData[2] == 'Q' && rawData[3] == 'P') {
            int frameEnd = rawData[7] & 0xff;
            return "[AMQP Protocol Header] type=0x" + Integer.toHexString(frameEnd)
                    + ", class=0x" + Integer.toHexString(rawData[6] & 0xff)
                    + ", version=" + ((rawData[5] & 0xff)) + "." + ((rawData[6] & 0xff));
        }

        int type = rawData[0] & 0xff;
        int channel = ((rawData[1] & 0xff) << 8) | (rawData[2] & 0xff);
        int payloadSize = 0;
        int idx = 3;
        int multiplier = 1;
        int bytesUsed = 0;
        while (idx < rawData.length && bytesUsed < 3) {
            int b = rawData[idx] & 0xff;
            payloadSize += (b & 0x7f) * multiplier;
            multiplier *= 128;
            idx++;
            bytesUsed++;
            if ((b & 0x80) == 0) {
                break;
            }
        }

        StringBuilder sb = new StringBuilder("[AMQP] ");
        sb.append("type=").append(toFrameType(type));
        sb.append(", channel=").append(channel);
        sb.append(", payload=").append(payloadSize).append("B");

        if (type == 0x01 && idx + 4 < rawData.length) {
            int classId = ((rawData[idx] & 0xff) << 8) | (rawData[idx + 1] & 0xff);
            int methodId = ((rawData[idx + 2] & 0xff) << 8) | (rawData[idx + 3] & 0xff);
            sb.append(", classId=").append(classId).append(" (").append(toClassName(classId)).append(')');
            sb.append(", methodId=").append(methodId).append(" (").append(toMethodName(classId, methodId)).append(')');
        }
        return sb.toString();
    }

    /**
    * 转为帧类型
    *
    * @param type 类型
    * @return 转为帧类型的结果
     */
    private static String toFrameType(int type) {
        return switch (type) {
            case 0x01 -> "METHOD";
            case 0x02 -> "HEADER";
            case 0x03 -> "BODY";
            case 0x08 -> "HEARTBEAT";
            default -> "Unknown(0x" + Integer.toHexString(type) + ")";
        };
    }

    /**
    * 转为类名称
    *
    * @param classId 类标识
    * @return 转为类名称的结果
     */
    private static String toClassName(int classId) {
        return switch (classId) {
            case 10 -> "Connection";
            case 20 -> "Channel";
            case 30 -> "Exchange";
            case 40 -> "Queue";
            case 50 -> "Basic";
            case 60 -> "Tx";
            default -> "Class" + classId;
        };
    }

    /**
    * 转为方法名称
    *
    * @param classId 类标识
    * @param methodId 方法标识
    * @return 转为方法名称的结果
     */
    private static String toMethodName(int classId, int methodId) {
        if (classId == 10) {
            return switch (methodId) {
                case 10 -> "Start";
                case 11 -> "Start-Ok";
                case 20 -> "Secure";
                case 30 -> "Tune";
                case 31 -> "Tune-Ok";
                case 40 -> "Open";
                case 41 -> "Open-Ok";
                case 50 -> "Close";
                case 51 -> "Close-Ok";
                default -> "Method" + methodId;
            };
        }
        if (classId == 40 || classId == 50) {
            return switch (methodId) {
                case 10 -> "Declare";
                case 11 -> "Declare-Ok";
                case 20 -> "Bind";
                case 21 -> "Bind-Ok";
                case 30 -> "Purge";
                case 40 -> "Delete";
                case 50 -> "Unbind";
                case 60 -> "Consume";
                case 61 -> "Consume-Ok";
                case 70 -> "Publish";
                case 80 -> "Deliver";
                case 90 -> "Ack";
                case 100 -> "Nack";
                default -> "Method" + methodId;
            };
        }
        return "Method" + methodId;
    }
}