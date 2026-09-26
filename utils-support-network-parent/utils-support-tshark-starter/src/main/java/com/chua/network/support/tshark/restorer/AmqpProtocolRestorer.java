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

    /**
     * 帧类型：方法帧
     */
    private static final int FRAME_METHOD = 0x01;

    /**
     * 帧类型：头部帧
     */
    private static final int FRAME_HEADER = 0x02;

    /**
     * 帧类型：主体帧
     */
    private static final int FRAME_BODY = 0x03;

    /**
     * 帧类型：心跳帧
     */
    private static final int FRAME_HEARTBEAT = 0x08;

    /**
     * 帧结束标记
     */
    private static final int FRAME_END = 0xce;

    /**
     * 帧固定开销：类型(1) + 通道(2) + 长度(4) + 帧尾(1)
     */
    private static final int FRAME_OVERHEAD = 8;

    @Override
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "amqp";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 170;
    }

    @Override
    /**
     * 是否可以Restore
    */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < FRAME_OVERHEAD) {
            return false;
        }
        // 协议头 "AMQP\0\0majorMinor" 首字节是 'A'，必须先于帧类型判定
        if (rawData[0] == 'A' && rawData[1] == 'M' && rawData[2] == 'Q' && rawData[3] == 'P') {
            return rawData.length >= 8 && rawData[4] == 0x00;
        }
        int type = rawData[0] & 0xff;
        if (type != FRAME_METHOD && type != FRAME_HEADER && type != FRAME_BODY && type != FRAME_HEARTBEAT) {
            return false;
        }
        if (type == FRAME_HEARTBEAT) {
            return rawData.length == FRAME_OVERHEAD && readSize(rawData) == 0 && isFrameEnd(rawData, FRAME_OVERHEAD - 1);
        }
        long size = readSize(rawData);
        return size >= 0 && FRAME_OVERHEAD + size <= rawData.length
                && isFrameEnd(rawData, (int) (FRAME_OVERHEAD - 1 + size));
    }

    /**
     * 读取帧的 payload 长度字段（偏移 3 起 4 字节无符号）。
     *
     * @param rawData 原始载荷
     * @return 长度值，超出 int 范围返回 -1
     */
    private static long readSize(byte[] rawData) {
        return ((long) (rawData[3] & 0xff) << 24) | ((rawData[4] & 0xff) << 16)
                | ((rawData[5] & 0xff) << 8) | (rawData[6] & 0xff);
    }

    /**
     * 判断指定下标是否为帧尾标记。
     *
     * @param rawData 原始载荷
     * @param index   下标
     * @return true 表示该处是 0xCE
     */
    private static boolean isFrameEnd(byte[] rawData, int index) {
        return index >= 0 && index < rawData.length && (rawData[index] & 0xff) == FRAME_END;
    }

    @Override
    /**
     * Restore
    */
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
