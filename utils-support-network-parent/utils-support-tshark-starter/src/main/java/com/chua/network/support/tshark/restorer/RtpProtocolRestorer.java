package com.chua.network.support.tshark.restorer;

/**
* RTP 协议还原器。
*
* <p>解析 RTP 头部：版本、padding、扩展、CSRC count、marker、payload type、sequence、timestamp、SSRC。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class RtpProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "rtp";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 140;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 12) {
            return false;
        }
        int b0 = rawData[0] & 0xff;
        int version = (b0 >> 6) & 0x03;
        if (version != 2) {
            return false;
        }
        int pt = rawData[1] & 0x7f;
        return pt <= 96 || (pt >= 96 && pt <= 127);
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 12) {
            return "[RTP] empty";
        }
        int b0 = rawData[0] & 0xff;
        int b1 = rawData[1] & 0xff;
        int version = (b0 >> 6) & 0x03;
        boolean padding = ((b0 >> 5) & 0x01) == 1;
        boolean extension = ((b0 >> 4) & 0x01) == 1;
        int csrcCount = b0 & 0x0f;
        boolean marker = ((b1 >> 7) & 0x01) == 1;
        int payloadType = b1 & 0x7f;
        int seqNum = ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        long timestamp = ((long) (rawData[4] & 0xff) << 24)
                | ((long) (rawData[5] & 0xff) << 16)
                | ((long) (rawData[6] & 0xff) << 8)
                | (rawData[7] & 0xff);
        long ssrc = ((long) (rawData[8] & 0xff) << 24)
                | ((long) (rawData[9] & 0xff) << 16)
                | ((long) (rawData[10] & 0xff) << 8)
                | (rawData[11] & 0xff);

        StringBuilder sb = new StringBuilder("[RTP] ");
        sb.append("version=").append(version);
        sb.append(", padding=").append(padding);
        sb.append(", extension=").append(extension);
        sb.append(", csrcCount=").append(csrcCount);
        sb.append(", marker=").append(marker);
        sb.append(", payloadType=").append(payloadType).append(" (").append(toPayloadTypeName(payloadType)).append(')');
        sb.append(", seqNum=").append(seqNum);
        sb.append(", timestamp=").append(timestamp);
        sb.append(", ssrc=0x").append(Long.toHexString(ssrc));
        return sb.toString();
    }

    /**
    * 转为payload类型名称
    *
    * @param pt pt
    * @return 转为payload类型名称的结果
    */
    private static String toPayloadTypeName(int pt) {
        return switch (pt) {
            case 0 -> "PCMU";
            case 3 -> "GSM";
            case 4 -> "G723";
            case 5 -> "DVI4";
            case 6 -> "DVI4";
            case 7 -> "LPC";
            case 8 -> "PCMA";
            case 9 -> "G722";
            case 10 -> "L16Stereo";
            case 11 -> "L16Mono";
            case 12 -> "QCELP";
            case 13 -> "CN";
            case 14 -> "MPA";
            case 15 -> "G728";
            case 16 -> "DVI4_11";
            case 17 -> "DVI4_22";
            case 18 -> "G729";
            case 25 -> "CelB";
            case 26 -> "JPEG";
            case 28 -> "NV";
            case 31 -> "H261";
            case 32 -> "MPV";
            case 33 -> "MP2T";
            case 34 -> "H263";
            default -> (pt >= 96 && pt <= 127) ? "Dynamic(" + pt + ")" : "Unknown(" + pt + ")";
        };
    }
}
