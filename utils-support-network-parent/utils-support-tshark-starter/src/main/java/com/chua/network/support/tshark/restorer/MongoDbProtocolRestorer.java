package com.chua.network.support.tshark.restorer;

/**
 * MongoDB Wire 协议 还原器。
 *
 * <p>MongoDB OP_MSG 报文：MessageHeader(16) + flagBits(4) + SectionKind(1) + Body。
 * 头部: 消息长度(4) + 请求id(4) + 响应转为(4) + op编码(4)。
 * op编码=2013 表示 OP_MSG。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MongoDbProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "mongodb";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 210;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 16) {
            return false;
        }
        int messageLength = ((rawData[0] & 0xff) << 24) | ((rawData[1] & 0xff) << 16)
                | ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        int opCode = ((rawData[12] & 0xff) << 24) | ((rawData[13] & 0xff) << 16)
                | ((rawData[14] & 0xff) << 8) | (rawData[15] & 0xff);
        return messageLength == rawData.length && opCode >= 0 && opCode <= 8000;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 16) {
            return "[MongoDB] empty";
        }
        int messageLength = ((rawData[0] & 0xff) << 24) | ((rawData[1] & 0xff) << 16)
                | ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        int requestId = ((rawData[4] & 0xff) << 24) | ((rawData[5] & 0xff) << 16)
                | ((rawData[6] & 0xff) << 8) | (rawData[7] & 0xff);
        int responseTo = ((rawData[8] & 0xff) << 24) | ((rawData[9] & 0xff) << 16)
                | ((rawData[10] & 0xff) << 8) | (rawData[11] & 0xff);
        int opCode = ((rawData[12] & 0xff) << 24) | ((rawData[13] & 0xff) << 16)
                | ((rawData[14] & 0xff) << 8) | (rawData[15] & 0xff);

        StringBuilder sb = new StringBuilder("[MongoDB] ");
        sb.append("opCode=").append(opCode).append(" (").append(toOpCodeName(opCode)).append(')');
        sb.append(", length=").append(messageLength);
        sb.append(", reqId=").append(requestId);
        sb.append(", respTo=").append(responseTo);
        if (rawData.length > 21) {
            sb.append(", sectionKind=0x").append(Integer.toHexString(rawData[20] & 0xff));
        }
        return sb.toString();
    }

    /**
     * 转为op编码名称
     *
     * @param op op
     * @return 转为op编码名称的结果
     */
    private static String toOpCodeName(int op) {
        return switch (op) {
            case 2004 -> "OP_QUERY";
            case 2010 -> "OP_INSERT";
            case 2011 -> "OP_GETMORE";
            case 2012 -> "OP_DELETE";
            case 2013 -> "OP_MSG";
            case 2014 -> "OP_KILLCURSORS";
            default -> "OP_" + op;
        };
    }
}
