package com.chua.network.support.tshark.restorer;

/**
   * 微信 (we对话 / MMTLS) 协议还原器。
 *
 * <p>微信使用自研 MMTLS 协议，TCP/UDP 长连接。
   * 还原层包括：MMTLS 握手头部识别（首字节 0x0a/0x12 表示 客户端/服务端 hello 类型）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WeChatProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "wechat";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 130;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return false;
        }
        int b0 = rawData[0] & 0xff;
        int b1 = rawData[1] & 0xff;
        int b2 = rawData[2] & 0xff;
        int b3 = rawData[3] & 0xff;
        return (b0 == 0x0a && (b1 == 0x00 || b1 == 0x01 || b1 == 0x02))
                || (b0 == 0x12 && b1 == 0x01)
                || (b0 == 0x16 && b1 == 0x03 && b2 == 0x01 && (b3 == 0x00 || b3 == 0x03));
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return "[WeChat] empty";
        }
        int b0 = rawData[0] & 0xff;
        int b1 = rawData[1] & 0xff;
        int b2 = rawData[2] & 0xff;
        int b3 = rawData[3] & 0xff;
        StringBuilder sb = new StringBuilder("[WeChat/MMTLS] ");
        if (b0 == 0x0a) {
            sb.append("ClientHello");
            sb.append(", type=").append(b1);
            if (rawData.length > 6) {
                int version = ((rawData[4] & 0xff) << 8) | (rawData[5] & 0xff);
                sb.append(", version=").append(version);
            }
        } else if (b0 == 0x12) {
            sb.append("ServerHello");
        } else if (b0 == 0x16) {
            sb.append("InnerTLS handshake, version=").append(b2).append('.').append(b3);
        } else {
            sb.append("Unknown subtype 0x").append(Integer.toHexString(b0));
        }
        sb.append(", length=").append(rawData.length);
        return sb.toString();
    }
}