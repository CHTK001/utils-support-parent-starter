package com.chua.network.support.tshark.restorer;

/**
 * DNS 协议还原器。
 *
 * <p>解析 DNS 查询/响应：查询名、记录类型、应答数量、应答内容。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DnsProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "dns";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 30;
    }

    @Override
    /**
     * 是否可以Restore
    */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 12) {
            return false;
        }
        // DNS header is 12 bytes; TransactionId (2) + Flags (2) + Counts (4*2 = 8)
        int flags = ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        int opcode = (flags >> 11) & 0x0f;
        return opcode <= 5;
    }

    @Override
    /**
     * Restore
    */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 12) {
            return "[DNS] empty payload";
        }
        int txnId = ((rawData[0] & 0xff) << 8) | (rawData[1] & 0xff);
        int flags = ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        int qdCount = ((rawData[4] & 0xff) << 8) | (rawData[5] & 0xff);
        int anCount = ((rawData[6] & 0xff) << 8) | (rawData[7] & 0xff);
        int nsCount = ((rawData[8] & 0xff) << 8) | (rawData[9] & 0xff);
        int arCount = ((rawData[10] & 0xff) << 8) | (rawData[11] & 0xff);

        StringBuilder sb = new StringBuilder("[DNS] ");
        sb.append("txnId=0x").append(Integer.toHexString(txnId));
        sb.append(", flags=0x").append(Integer.toHexString(flags));
        boolean isResponse = (flags & 0x8000) != 0;
        int rcode = flags & 0x0f;
        sb.append(", ").append(isResponse ? "RESPONSE" : "QUERY");
        sb.append(", rcode=").append(toRcode(rcode));
        sb.append(", questions=").append(qdCount);
        sb.append(", answers=").append(anCount);
        sb.append(", authority=").append(nsCount);
        sb.append(", additional=").append(arCount);

 // 解析 查询 段
        int idx = 12;
        for (int i = 0; i < qdCount && idx < rawData.length; i++) {
            String name = readName(rawData, idx);
            if (name == null) {
                break;
            }
            int nameLen = jumpLength(rawData, idx);
            if (nameLen < 0 || idx + nameLen + 4 > rawData.length) {
                break;
            }
            int qtype = ((rawData[idx + nameLen] & 0xff) << 8) | (rawData[idx + nameLen + 1] & 0xff);
            int qclass = ((rawData[idx + nameLen + 2] & 0xff) << 8) | (rawData[idx + nameLen + 3] & 0xff);
            sb.append("\n  Q[").append(i + 1).append("] ").append(name);
            sb.append(" type=").append(toTypeName(qtype));
            sb.append(" class=").append(toClassName(qclass));
            idx += nameLen + 4;
        }

        // 解析 Answer 段
        for (int i = 0; i < anCount && idx < rawData.length; i++) {
            String name = readName(rawData, idx);
            int nameLen = jumpLength(rawData, idx);
            if (nameLen < 0 || idx + nameLen + 10 > rawData.length) {
                break;
            }
            int atype = ((rawData[idx + nameLen] & 0xff) << 8) | (rawData[idx + nameLen + 1] & 0xff);
            int aclass = ((rawData[idx + nameLen + 2] & 0xff) << 8) | (rawData[idx + nameLen + 3] & 0xff);
            int ttl = ((rawData[idx + nameLen + 4] & 0xff) << 24)
                    | ((rawData[idx + nameLen + 5] & 0xff) << 16)
                    | ((rawData[idx + nameLen + 6] & 0xff) << 8)
                    | (rawData[idx + nameLen + 7] & 0xff);
            int rdLen = ((rawData[idx + nameLen + 8] & 0xff) << 8) | (rawData[idx + nameLen + 9] & 0xff);
            sb.append("\n  A[").append(i + 1).append("] ").append(name == null ? "?" : name);
            sb.append(" type=").append(toTypeName(atype));
            sb.append(" ttl=").append(ttl).append("s");
            sb.append(" data=").append(rdLen).append("B");
            if (atype == 0x0001 && rdLen == 4 && idx + nameLen + 10 + 4 <= rawData.length) {
                sb.append(" ip=")
                        .append(rawData[idx + nameLen + 10] & 0xff).append('.')
                        .append(rawData[idx + nameLen + 11] & 0xff).append('.')
                        .append(rawData[idx + nameLen + 12] & 0xff).append('.')
                        .append(rawData[idx + nameLen + 13] & 0xff);
            }
            idx += nameLen + 10 + rdLen;
        }
        return sb.toString();
    }

    /**
     * 读取 DNS 域名（处理 标签 与 pointer）。
     *
     * @param data 完整 DNS 报文
     * @param idx  起始偏移
     * @return 域名字符串
     */
    private static String readName(byte[] data, int idx) {
        StringBuilder sb = new StringBuilder();
        int hops = 0;
        boolean jumped = false;
        int origIdx = idx;
        int loopGuard = 0;
        while (idx < data.length && loopGuard++ < 64) {
            int len = data[idx] & 0xff;
            if (len == 0) {
                if (!jumped) {
                    return sb.length() > 0 ? sb.substring(0, sb.length() - 1) : "<root>";
                }
                return sb.length() > 0 ? sb.substring(0, sb.length() - 1) : "<root>";
            }
            if ((len & 0xc0) == 0xc0) {
                if (idx + 1 >= data.length) {
                    return null;
                }
                int offset = ((len & 0x3f) << 8) | (data[idx + 1] & 0xff);
                if (!jumped) {
                    jumped = true;
                    idx = offset;
                } else {
                    return null;
                }
                if (++hops > 4) {
                    return null;
                }
                continue;
            }
            if (idx + 1 + len > data.length) {
                return null;
            }
            sb.append(toText(java.util.Arrays.copyOfRange(data, idx + 1, idx + 1 + len))).append('.');
            idx += 1 + len;
        }
        return null;
    }

    /**
     * 跳过域名标签所需的字节数（不解析内容）。
     *
     * @param data 报文
     * @param idx  起始偏移
     * @return 字节数
     */
    private static int jumpLength(byte[] data, int idx) {
        int len = 0;
        int loopGuard = 0;
        while (idx < data.length && loopGuard++ < 64) {
            int labelLen = data[idx] & 0xff;
            if (labelLen == 0) {
                len += 1;
                return len;
            }
            if ((labelLen & 0xc0) == 0xc0) {
                return len + 2;
            }
            idx += 1 + labelLen;
            len += 1 + labelLen;
        }
        return -1;
    }

    /**
     * 资源记录类型转可读名称。
     * @param type 类型
     * @return 转为类型名称的结果
     */
    private static String toTypeName(int type) {
        return switch (type) {
            case 0x0001 -> "A";
            case 0x0002 -> "NS";
            case 0x0005 -> "CNAME";
            case 0x0006 -> "SOA";
            case 0x000c -> "PTR";
            case 0x000d -> "HINFO";
            case 0x000f -> "MX";
            case 0x0010 -> "TXT";
            case 0x0011 -> "RP";
            case 0x001c -> "AAAA";
            case 0x001e -> "SPF";
            case 0x0021 -> "SRV";
            case 0x0026 -> "DS";
            case 0x0100 -> "OPT";
            default -> "TYPE" + type;
        };
    }

    /**
     * DNS 类（类）转可读名称。
     * @param cls cls
     * @return 转为类名称的结果
     */
    private static String toClassName(int cls) {
        return switch (cls) {
            case 0x0001 -> "IN";
            case 0x0002 -> "CS";
            case 0x0003 -> "CH";
            case 0x0004 -> "HS";
            case 0x00ff -> "ANY";
            default -> "CLASS" + cls;
        };
    }

    /**
     * DNS RCODE 转可读名称。
     * @param rcode rcode
     * @return 转为rcode的结果
     */
    private static String toRcode(int rcode) {
        return switch (rcode) {
            case 0 -> "NOERROR";
            case 1 -> "FORMERR";
            case 2 -> "SERVFAIL";
            case 3 -> "NXDOMAIN";
            case 4 -> "NOTIMP";
            case 5 -> "REFUSED";
            default -> "RCODE" + rcode;
        };
    }
}
