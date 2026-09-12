package com.chua.network.support.tshark.restorer;

/**
 * CoAP 协议还原器。
 *
 * <p>CoAP（受限应用协议）头部：
 * <ul>
 *   <li>byte 0: Ver(2) + Type(2) + Token Length(4)</li>
 *   <li>byte 1: Code (class.detail)</li>
 *   <li>byte 2-3: MessageId</li>
 *   <li>Token (0-8 bytes)</li>
 *   <li>Options + Payload</li>
 * </ul>
   * 类型: 0=CON, 1=NON, 2=ACK, 3=RST。
   * 编码: 0.01=获取, 0.02=POST, 0.04=删除, 2.05=内容, 4.04=Not Found。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CoapProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "coap";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 270;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return false;
        }
        int version = (rawData[0] >> 6) & 0x03;
        int type = (rawData[0] >> 4) & 0x03;
        int tokenLen = rawData[0] & 0x0f;
        return version == 1 && type >= 0 && type <= 3 && tokenLen <= 8;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return "[CoAP] empty";
        }
        int version = (rawData[0] >> 6) & 0x03;
        int type = (rawData[0] >> 4) & 0x03;
        int tokenLen = rawData[0] & 0x0f;
        int code = rawData[1] & 0xff;
        int codeClass = (code >> 5) & 0x07;
        int codeDetail = code & 0x1f;
        int messageId = ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);

        StringBuilder sb = new StringBuilder("[CoAP] ");
        sb.append("version=").append(version);
        sb.append(", type=").append(toTypeName(type));
        sb.append(", code=").append(codeClass).append('.').append(String.format("%02d", codeDetail));
        sb.append(" (").append(toCodeName(codeClass, codeDetail)).append(')');
        sb.append(", msgId=0x").append(Integer.toHexString(messageId));
        sb.append(", tokenLen=").append(tokenLen);
        return sb.toString();
    }

    /**
     * 转为类型名称
     *
     * @param type 类型
     * @return 转为类型名称的结果
     */
    private static String toTypeName(int type) {
        return switch (type) {
            case 0 -> "CON";
            case 1 -> "NON";
            case 2 -> "ACK";
            case 3 -> "RST";
            default -> "Unknown";
        };
    }

    /**
     * 转为编码名称
     *
     * @param cls cls
     * @param detail detail
     * @return 转为编码名称的结果
     */
    private static String toCodeName(int cls, int detail) {
        if (cls == 0) {
            return switch (detail) {
                case 1 -> "GET";
                case 2 -> "POST";
                case 3 -> "PUT";
                case 4 -> "DELETE";
                default -> "Method_" + detail;
            };
        }
        if (cls == 2) {
            return switch (detail) {
                case 1 -> "Created";
                case 2 -> "Deleted";
                case 3 -> "Valid";
                case 4 -> "Changed";
                case 5 -> "Content";
                default -> "Success_" + detail;
            };
        }
        if (cls == 4) {
            return switch (detail) {
                case 0 -> "BadRequest";
                case 1 -> "Unauthorized";
                case 4 -> "NotFound";
                case 5 -> "MethodNotAllowed";
                default -> "ClientErr_" + detail;
            };
        }
        if (cls == 5) {
            return "ServerErr_" + detail;
        }
        return "Code_" + detail;
    }
}