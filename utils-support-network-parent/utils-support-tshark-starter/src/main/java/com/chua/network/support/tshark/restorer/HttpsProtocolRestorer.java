package com.chua.network.support.tshark.restorer;

/**
 * HTTPS/TLS 协议还原器。
 *
 * <p>解析 TLS ClientHello 与 ServerHello，提取 SNI（Server Name Indication）、
 * ciphersuite 等关键字段。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HttpsProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "https";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 20;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 5) {
            return false;
        }
        // TLS 记录：ContentType (1) + Version (2) + Length (2)
        if (rawData[0] != 0x16) {
            return false;
        }
        int version = ((rawData[1] & 0xff) << 8) | (rawData[2] & 0xff);
        return version == 0x0301 || version == 0x0302 || version == 0x0303;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 5) {
            return "[HTTPS] empty";
        }
        StringBuilder sb = new StringBuilder("[HTTPS/TLS] ");
        int version = ((rawData[1] & 0xff) << 8) | (rawData[2] & 0xff);
        sb.append("version=").append(toVersionString(version));
        int contentType = rawData[0] & 0xff;
        sb.append(", contentType=").append(toContentTypeString(contentType));
        int length = ((rawData[3] & 0xff) << 8) | (rawData[4] & 0xff);
        sb.append(", length=").append(length);

 // 尝试解析 客户端hello 中的 SNI
        if (rawData.length > 43 && contentType == 0x16) {
            int handshakeType = rawData[5] & 0xff;
            if (handshakeType == 0x01) {
                sb.append(", handshake=ClientHello");
                String sni = extractSni(rawData);
                if (sni != null) {
                    sb.append(", SNI=").append(sni);
                }
            } else if (handshakeType == 0x02) {
                sb.append(", handshake=ServerHello");
            }
        }
        return sb.toString();
    }

    /**
     * 从 客户端hello 中提取 SNI。
     *
     * @param data TLS 记录字节
     * @return SNI 域名，若无法提取返回 空
     */
    private static String extractSni(byte[] data) {
        try {
            int idx = 43;
            if (idx >= data.length) {
                return null;
            }
            int sessionIdLen = data[idx] & 0xff;
            idx += 1 + sessionIdLen;
            if (idx + 2 > data.length) {
                return null;
            }
            int cipherSuitesLen = ((data[idx] & 0xff) << 8) | (data[idx + 1] & 0xff);
            idx += 2 + cipherSuitesLen;
            if (idx + 1 > data.length) {
                return null;
            }
            int compressionMethodsLen = data[idx] & 0xff;
            idx += 1 + compressionMethodsLen;
            if (idx + 2 > data.length) {
                return null;
            }
            int extensionsLen = ((data[idx] & 0xff) << 8) | (data[idx + 1] & 0xff);
            idx += 2;
            int extensionsEnd = idx + extensionsLen;
            while (idx + 4 <= extensionsEnd && idx + 4 <= data.length) {
                int extensionType = ((data[idx] & 0xff) << 8) | (data[idx + 1] & 0xff);
                int extensionLen = ((data[idx + 2] & 0xff) << 8) | (data[idx + 3] & 0xff);
                idx += 4;
                if (extensionType == 0x0000 && extensionLen >= 5) {
                    int nameListLen = ((data[idx + 3] & 0xff) << 8) | (data[idx + 4] & 0xff);
                    int nameStart = idx + 5;
                    int nameEnd = nameStart + nameListLen;
                    if (nameEnd <= data.length) {
                        return toText(java.util.Arrays.copyOfRange(data, nameStart, nameEnd));
                    }
                }
                idx += extensionLen;
            }
        } catch (Exception ignored) {
            // 解析失败忽略
        }
        return null;
    }

    /**
     * TLS 版本号转可读字符串。
     *
     * @param version 原始版本号
     * @return 版本字符串
     */
    private static String toVersionString(int version) {
        return switch (version) {
            case 0x0300 -> "SSLv3";
            case 0x0301 -> "TLSv1.0";
            case 0x0302 -> "TLSv1.1";
            case 0x0303 -> "TLSv1.2";
            case 0x0304 -> "TLSv1.3";
            default -> "0x" + Integer.toHexString(version);
        };
    }

    /**
     * TLS 内容类型 编号转可读字符串。
     *
     * @param type 原始类型编号
     * @return 类型字符串
     */
    private static String toContentTypeString(int type) {
        return switch (type) {
            case 0x14 -> "ChangeCipherSpec";
            case 0x15 -> "Alert";
            case 0x16 -> "Handshake";
            case 0x17 -> "ApplicationData";
            default -> "0x" + Integer.toHexString(type);
        };
    }
}
