package com.chua.network.support.tshark.restorer;

/**
* ARP 协议还原器。
*
* <p>解析 ARP 请求/应答：发送端 MAC/IP、目标 MAC/IP。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class ArpProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "arp";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 100;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 28) {
            return false;
        }
        int hType = ((rawData[0] & 0xff) << 8) | (rawData[1] & 0xff);
        int pType = ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        int op = ((rawData[6] & 0xff) << 8) | (rawData[7] & 0xff);
        return hType == 0x0001 && pType == 0x0800 && (op == 1 || op == 2);
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 28) {
            return "[ARP] empty";
        }
        int op = ((rawData[6] & 0xff) << 8) | (rawData[7] & 0xff);
        String senderMac = macToString(rawData, 8);
        String senderIp = ipToString(rawData, 14);
        String targetMac = macToString(rawData, 18);
        String targetIp = ipToString(rawData, 24);

        StringBuilder sb = new StringBuilder("[ARP] ");
        sb.append(op == 1 ? "REQUEST" : "REPLY");
        sb.append(", sender=").append(senderIp).append('/').append(senderMac);
        sb.append(", target=").append(targetIp).append('/').append(targetMac);
        return sb.toString();
    }

    /**
    * mac转为字符串
    *
    * @param data 数据
    * @param offset 偏移量
    * @return mac转为字符串的结果
    */
    private static String macToString(byte[] data, int offset) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", data[offset + i] & 0xff));
        }
        return sb.toString();
    }

    /**
    * ip转为字符串
    *
    * @param data 数据
    * @param offset 偏移量
    * @return ip转为字符串的结果
    */
    private static String ipToString(byte[] data, int offset) {
        return (data[offset] & 0xff) + "."
                + (data[offset + 1] & 0xff) + "."
                + (data[offset + 2] & 0xff) + "."
                + (data[offset + 3] & 0xff);
    }
}
