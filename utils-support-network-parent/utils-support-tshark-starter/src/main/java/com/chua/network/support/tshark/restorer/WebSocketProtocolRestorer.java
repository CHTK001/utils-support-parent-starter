package com.chua.network.support.tshark.restorer;

/**
 * WebSocket 协议还原器。
 *
 * <p>WebSocket 帧格式：
 * <ul>
 *   <li>byte 0: FIN(1) + RSV1-3(3) + opcode(4)</li>
 *   <li>byte 1: MASK(1) + payloadLength(7)</li>
 *   <li>扩展长度: 2 bytes (length=126) 或 8 bytes (length=127)</li>
 *   <li>masking key (4 bytes, only if MASK=1)</li>
 *   <li>payload (with mask XOR)</li>
 * </ul>
 * opcode: 0x0=续帧, 0x1=文本, 0x2=二进制, 0x8=关闭, 0x9=ping, 0xa=pong</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WebSocketProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * opcode：续帧
     */
    private static final int OP_CONTINUATION = 0x0;

    /**
     * opcode：文本帧
     */
    private static final int OP_TEXT = 0x1;

    /**
     * opcode：二进制帧
     */
    private static final int OP_BINARY = 0x2;

    /**
     * opcode：关闭
     */
    private static final int OP_CLOSE = 0x8;

    /**
     * opcode：PING
     */
    private static final int OP_PING = 0x9;

    /**
     * opcode：PONG
     */
    private static final int OP_PONG = 0xa;

    /**
     * 短长度取值上限，等于 126/127 时需读取扩展长度
     */
    private static final int LEN_EXTENDED_16 = 126;

    /**
     * 扩展长度切换值，等于该值时使用 8 字节长度
     */
    private static final int LEN_EXTENDED_64 = 127;

    @Override
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "websocket";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 250;
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
        int b0 = rawData[0] & 0xff;
        int opcode = b0 & 0x0f;
        if ((b0 & 0x80) == 0 || (b0 & 0x70) != 0 || !isKnownOpcode(opcode)) {
            return false;
        }
        int b1 = rawData[1] & 0xff;
        long payloadLen = b1 & 0x7f;
        int idx = 2;
        if (payloadLen == LEN_EXTENDED_16) {
            if (idx + 2 > rawData.length) {
                return false;
            }
            payloadLen = ((rawData[idx] & 0xff) << 8) | (rawData[idx + 1] & 0xff);
            idx += 2;
        } else if (payloadLen == LEN_EXTENDED_64) {
            if (idx + 8 > rawData.length) {
                return false;
            }
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | (rawData[idx + i] & 0xff);
            }
            idx += 8;
        }
        if ((b1 & 0x80) != 0) {
            idx += 4;
        }
        return payloadLen >= 0 && idx + payloadLen <= rawData.length;
    }

    /**
     * 判断 opcode 是否为 RFC 6455 定义的取值。
     *
     * @param opcode 操作码
     * @return true 表示已定义
     */
    private static boolean isKnownOpcode(int opcode) {
        return opcode == OP_CONTINUATION || opcode == OP_TEXT || opcode == OP_BINARY
                || opcode == OP_CLOSE || opcode == OP_PING || opcode == OP_PONG;
    }

    @Override
    /**
     * Restore
    */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 2) {
            return "[WebSocket] empty";
        }
        int b0 = rawData[0] & 0xff;
        int b1 = rawData[1] & 0xff;
        boolean fin = (b0 & 0x80) != 0;
        int rsv = (b0 >> 4) & 0x07;
        int opcode = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long payloadLen = b1 & 0x7f;

        int idx = 2;
        if (payloadLen == 126) {
            if (idx + 2 > rawData.length) {
                return "[WebSocket] truncated extended length";
            }
            payloadLen = ((rawData[idx] & 0xff) << 8) | (rawData[idx + 1] & 0xff);
            idx += 2;
        } else if (payloadLen == 127) {
            if (idx + 8 > rawData.length) {
                return "[WebSocket] truncated extended length";
            }
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | (rawData[idx + i] & 0xff);
            }
            idx += 8;
        }

        byte[] maskKey = null;
        if (masked) {
            if (idx + 4 > rawData.length) {
                return "[WebSocket] truncated mask";
            }
            maskKey = new byte[4];
            System.arraycopy(rawData, idx, maskKey, 0, 4);
            idx += 4;
        }

        StringBuilder sb = new StringBuilder("[WebSocket] ");
        sb.append("FIN=").append(fin);
        sb.append(", RSV=").append(rsv);
        sb.append(", opcode=").append(String.format("0x%x", opcode)).append(" (").append(toOpcodeName(opcode)).append(')');
        sb.append(", MASK=").append(masked);
        sb.append(", payload=").append(payloadLen).append("B");

        if (payloadLen > 0 && payloadLen <= 4096 && idx < rawData.length) {
            long actualLen = Math.min(payloadLen, rawData.length - idx);
            byte[] payloadBytes = new byte[(int) actualLen];
            System.arraycopy(rawData, idx, payloadBytes, 0, (int) actualLen);
            if (masked && maskKey != null) {
                for (int i = 0; i < payloadBytes.length; i++) {
                    payloadBytes[i] = (byte) (payloadBytes[i] ^ maskKey[i % 4]);
                }
            }
            if (opcode == 0x01) {
                String text = toText(payloadBytes).trim();
                if (text.length() > 256) {
                    text = text.substring(0, 256) + "...(truncated)";
                }
                sb.append("\n  TEXT: ").append(text);
            }
        }
        return sb.toString();
    }

    /**
     * 转为opcode名称
     *
     * @param opcode opcode
     * @return 转为opcode名称的结果
     */
    private static String toOpcodeName(int opcode) {
        return switch (opcode) {
            case 0x0 -> "Continuation";
            case 0x1 -> "Text";
            case 0x2 -> "Binary";
            case 0x8 -> "Close";
            case 0x9 -> "Ping";
            case 0xa -> "Pong";
            case 0xf -> "Reserved";
            default -> "Unknown";
        };
    }
}
