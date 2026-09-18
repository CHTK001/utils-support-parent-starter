package com.chua.network.support.tshark.restorer;

/**
* DHCP 协议还原器。
*
* <p>解析 DHCP 报文类型与 option 53（Message Type）、option 55（Parameter Request List）。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DhcpProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "dhcp";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 80;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 240) {
            return false;
        }
 // BOOTP/DHCP 魔法 Cookie: 99.130.83.99
        int m1 = rawData[236] & 0xff;
        int m2 = rawData[237] & 0xff;
        int m3 = rawData[238] & 0xff;
        int m4 = rawData[239] & 0xff;
        return m1 == 99 && m2 == 130 && m3 == 83 && m4 == 99;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 240) {
            return "[DHCP] empty";
        }
        int op = rawData[0] & 0xff;
        int hType = rawData[1] & 0xff;
        int hLen = rawData[2] & 0xff;
        int xid = ((rawData[4] & 0xff) << 24)
                | ((rawData[5] & 0xff) << 16)
                | ((rawData[6] & 0xff) << 8)
                | (rawData[7] & 0xff);

        StringBuilder sb = new StringBuilder("[DHCP] ");
        sb.append(op == 1 ? "REQUEST" : "REPLY");
        sb.append(", hType=").append(hType).append(", hLen=").append(hLen);
        sb.append(", xid=0x").append(Integer.toHexString(xid));

 // 解析 期权
        int idx = 240;
        String messageType = null;
        int requestedIp = 0;
        while (idx + 1 < rawData.length) {
            int opt = rawData[idx] & 0xff;
            int len = rawData[idx + 1] & 0xff;
            if (opt == 0xff) {
                sb.append(", end");
                break;
            }
            if (opt == 0x00) {
                idx++;
                continue;
            }
            if (idx + 2 + len > rawData.length) {
                break;
            }
            if (opt == 0x35 && len >= 1) {
                int msgType = rawData[idx + 2] & 0xff;
                messageType = toMessageTypeName(msgType);
                sb.append(", msgType=").append(messageType);
            } else if (opt == 0x32 && len >= 4) {
                requestedIp = ((rawData[idx + 2] & 0xff) << 24)
                        | ((rawData[idx + 3] & 0xff) << 16)
                        | ((rawData[idx + 4] & 0xff) << 8)
                        | (rawData[idx + 5] & 0xff);
                sb.append(", requestedIp=").append(ipToString(requestedIp));
            } else if (opt == 0x01 && len >= 4) {
                int subnet = ((rawData[idx + 2] & 0xff) << 24)
                        | ((rawData[idx + 3] & 0xff) << 16)
                        | ((rawData[idx + 4] & 0xff) << 8)
                        | (rawData[idx + 5] & 0xff);
                sb.append(", subnet=").append(ipToString(subnet));
            } else if (opt == 0x03 && len >= 4) {
                int router = ((rawData[idx + 2] & 0xff) << 24)
                        | ((rawData[idx + 3] & 0xff) << 16)
                        | ((rawData[idx + 4] & 0xff) << 8)
                        | (rawData[idx + 5] & 0xff);
                sb.append(", router=").append(ipToString(router));
            } else if (opt == 0x06 && len >= 4) {
                int dns = ((rawData[idx + 2] & 0xff) << 24)
                        | ((rawData[idx + 3] & 0xff) << 16)
                        | ((rawData[idx + 4] & 0xff) << 8)
                        | (rawData[idx + 5] & 0xff);
                sb.append(", dns=").append(ipToString(dns));
            } else if (opt == 0x37 && len >= 4) {
                int serverId = ((rawData[idx + 2] & 0xff) << 24)
                        | ((rawData[idx + 3] & 0xff) << 16)
                        | ((rawData[idx + 4] & 0xff) << 8)
                        | (rawData[idx + 5] & 0xff);
                sb.append(", serverId=").append(ipToString(serverId));
            }
            idx += 2 + len;
        }
        return sb.toString();
    }

    /**
    * 转为消息类型名称
    *
    * @param type 类型
    * @return 转为消息类型名称的结果
    */
    private static String toMessageTypeName(int type) {
        return switch (type) {
            case 1 -> "DISCOVER";
            case 2 -> "OFFER";
            case 3 -> "REQUEST";
            case 4 -> "DECLINE";
            case 5 -> "ACK";
            case 6 -> "NAK";
            case 7 -> "RELEASE";
            case 8 -> "INFORM";
            default -> "TYPE" + type;
        };
    }

    /**
    * ip转为字符串
    *
    * @param ip ip
    * @return ip转为字符串的结果
    */
    private static String ipToString(int ip) {
        return ((ip >> 24) & 0xff) + "."
                + ((ip >> 16) & 0xff) + "."
                + ((ip >> 8) & 0xff) + "."
                + (ip & 0xff);
    }
}
