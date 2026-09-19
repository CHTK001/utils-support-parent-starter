package com.chua.network.support.tshark.restorer;

/**
 * ICMP 协议还原器。
 *
 * <p>解析 ICMP type/code 并给出常见组合的可读描述。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class IcmpProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "icmp";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 70;
    }

    @Override
    /**
     * 是否可以Restore
    */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 8) {
            return false;
        }
        int type = rawData[0] & 0xff;
        return type >= 0 && type <= 43;
    }

    @Override
    /**
     * Restore
    */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 8) {
            return "[ICMP] empty";
        }
        int type = rawData[0] & 0xff;
        int code = rawData[1] & 0xff;
        int id = ((rawData[4] & 0xff) << 8) | (rawData[5] & 0xff);
        int seq = ((rawData[6] & 0xff) << 8) | (rawData[7] & 0xff);
        StringBuilder sb = new StringBuilder("[ICMP] type=");
        sb.append(type).append(" (").append(toTypeName(type)).append(')');
        sb.append(", code=").append(code).append(" (").append(toCodeName(type, code)).append(')');
        if (type == 8 || type == 0 || type == 13 || type == 14 || type == 17) {
            sb.append(", id=0x").append(Integer.toHexString(id));
            sb.append(", seq=").append(seq);
        }
        if (type == 3) {
            int nextHopMtu = rawData.length >= 8
                    ? ((rawData[6] & 0xff) << 8) | (rawData[7] & 0xff)
                    : 0;
            sb.append(", nextHopMtu=").append(nextHopMtu);
        }
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
            case 0 -> "EchoReply";
            case 3 -> "DestinationUnreachable";
            case 4 -> "SourceQuench";
            case 5 -> "Redirect";
            case 8 -> "EchoRequest";
            case 9 -> "RouterAdvertisement";
            case 10 -> "RouterSolicitation";
            case 11 -> "TimeExceeded";
            case 12 -> "ParameterProblem";
            case 13 -> "TimestampRequest";
            case 14 -> "TimestampReply";
            case 17 -> "AddressMaskRequest";
            case 18 -> "AddressMaskReply";
            default -> "Type" + type;
        };
    }

    /**
     * 转为编码名称
     *
     * @param type 类型
     * @param code 编码
     * @return 转为编码名称的结果
     */
    private static String toCodeName(int type, int code) {
        if (type == 3) {
            return switch (code) {
                case 0 -> "NetworkUnreachable";
                case 1 -> "HostUnreachable";
                case 2 -> "ProtocolUnreachable";
                case 3 -> "PortUnreachable";
                case 4 -> "FragmentationNeeded";
                case 5 -> "SourceRouteFailed";
                default -> "Code" + code;
            };
        }
        if (type == 5) {
            return switch (code) {
                case 0 -> "RedirectForNetwork";
                case 1 -> "RedirectForHost";
                default -> "Code" + code;
            };
        }
        if (type == 11) {
            return switch (code) {
                case 0 -> "TTLExceededInTransit";
                case 1 -> "FragmentReassemblyTimeExceeded";
                default -> "Code" + code;
            };
        }
        return code == 0 ? "NoCode" : "Code" + code;
    }
}
