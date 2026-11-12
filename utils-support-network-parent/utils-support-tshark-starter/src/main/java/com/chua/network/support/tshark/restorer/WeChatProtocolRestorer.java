// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.network.support.tshark.restorer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 微信协议还原器（MMTLS 记录解析 + 连接级识别 + 明文字段）。
 *
 * <h3>MMTLS 是什么</h3>
 * <p>微信没有用标准 TLS，而是自研的 MMTLS：记录头与 TLS 1.3 同形
 * （{@code ContentType(1) | Version(2) | Length(2) | Fragment}），但版本号不是
 * {@code 0x0301~0x0303} 而是 {@code 0xF103}。这个版本号是它最硬的指纹——
 * 标准 TLS 永远不会出现，因此可以据此把微信长连接从一堆 HTTPS 里挑出来。</p>
 *
 * <h3>本还原器做三层</h3>
 * <ol>
 *   <li><b>记录层</b>：按 5 字节头切记录，报每条记录的类型（handshake / appdata /
 *       alert / change_cipher_spec）与字节数；只有<b>整段恰好切完</b>才认，避免把
 *       密文里的巧合字节当成记录头；</li>
 *   <li><b>握手层</b>：handshake 记录里第一个字节是握手类型，报出
 *       ClientHello / ServerHello / Certificate / Finished 等；</li>
 *   <li><b>连接层</b>：按 TLS SNI / HTTP Host（{@code *.weixin.qq.com}、
 *       {@code *.wxs.qq.com} 等）认连接，给出「谁在跟谁说话」。</li>
 * </ol>
 *
 * <h3>不做什么</h3>
 * <p>MMTLS 的 appdata 是加密的，且没有 keylog 机制（不像 TLS 有 SSLKEYLOGFILE），
 * 所以<b>不可能还原聊天内容</b>。这里只报记录结构与连接事实，不把密文里的
 * 可打印片段当成昵称、账号或消息体。</p>
 *
 * <p><b>关于 0xF103 的依据</b>：该版本号来自公开的 MMTLS 逆向分析结论，
 * 不是猜测；但为了稳妥，判定仍要求「整段记录自洽」，单帧偶然命中不会被认。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WeChatProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * 微信常用域名后缀（含企业微信 work.weixin.qq.com 与图片 CDN）
     */
    private static final List<String> DOMAINS = List.of(
            "weixin.qq.com", "wx.qq.com", "wechat.com", "wxs.qq.com", "weixin.com",
            "qpic.cn", "wechatpay.cn");

    /**
     * MMTLS 记录头长度：ContentType + Version + Length
     */
    private static final int RECORD_HEADER = 5;

    /**
     * 记录类型：change_cipher_spec
     */
    private static final int CT_CHANGE_CIPHER_SPEC = 0x14;

    /**
     * 记录类型：alert
     */
    private static final int CT_ALERT = 0x15;

    /**
     * 记录类型：handshake
     */
    private static final int CT_HANDSHAKE = 0x16;

    /**
     * 记录类型：application_data
     */
    private static final int CT_APPDATA = 0x17;

    /**
     * MMTLS 版本号高字节（0xF103）
     */
    private static final int MMTLS_VERSION_HI = 0xF1;

    /**
     * MMTLS 版本号低字节（0xF103）
     */
    private static final int MMTLS_VERSION_LO = 0x03;

    /**
     * 最多解析多少条记录（长连接一帧里可能拼了很多条）
     */
    private static final int MAX_RECORDS = 16;

    /**
     * 一条已切出的 MMTLS 记录。
     *
     * @param type     记录类型
     * @param length   声明的载荷长度
     * @param complete 该记录是否完整（抓包截断时为 false）
     */
    protected record MmtlsRecord(int type, int length, boolean complete) {
    }

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "wechat";
    }

    /**
     * 获取Priority
     *
     * <p>必须早于 {@link HttpsProtocolRestorer}（20）：同一帧既是 TLS 又是微信时，
     * 应用层标签比「一条 TLS 记录」有用。</p>
     */
    @Override
    public int getPriority() {
        return 15;
    }

    /**
     * 是否可以Restore
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (host(protocolInfo) != null) {
            return true;
        }
        return !parseRecords(rawData).isEmpty();
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[WeChat] empty";
        }
        List<MmtlsRecord> records = parseRecords(rawData);
        if (!records.isEmpty()) {
            return describeRecords(rawData, records);
        }
        String host = host(protocolInfo);
        if (host != null) {
            StringBuilder sb = new StringBuilder("[WeChat] host=").append(host);
            sb.append(", bytes=").append(rawData.length);
            boolean handshake = rawData.length >= 4 && (rawData[0] & 0xff) == 0x16;
            if (handshake) {
                sb.append(", tls=handshake");
            } else {
                AppTrafficDecoder.appendFields(sb, rawData, 6);
            }
            return sb.toString();
        }
        return "[WeChat] bytes=" + rawData.length + ", payload=encrypted";
    }

    /**
     * 解析 MMTLS 记录。
     *
     * <p>规则很严：版本必须是 {@code 0xF103}、类型必须是四种已知值、记录之间必须首尾相接
     * （最后一条允许因抓包截断而不完整）。任何一处不符就返回空，交给别的还原器——
     * 宁可漏认，也不把普通 TLS 或密文标成微信。</p>
     *
     * @param rawData 载荷
     * @return 记录列表，不可识别返回空
     */
    protected static List<MmtlsRecord> parseRecords(byte[] rawData) {
        List<MmtlsRecord> records = new ArrayList<>();
        if (rawData == null || rawData.length <= RECORD_HEADER) {
            return records;
        }
        int pos = 0;
        while (pos + RECORD_HEADER <= rawData.length) {
            int type = rawData[pos] & 0xff;
            int hi = rawData[pos + 1] & 0xff;
            int lo = rawData[pos + 2] & 0xff;
            if (hi != MMTLS_VERSION_HI || lo != MMTLS_VERSION_LO || !isKnownType(type)) {
                return List.of();
            }
            int length = ((rawData[pos + 3] & 0xff) << 8) | (rawData[pos + 4] & 0xff);
            if (length <= 0) {
                return List.of();
            }
            boolean complete = pos + RECORD_HEADER + length <= rawData.length;
            records.add(new MmtlsRecord(type, length, complete));
            if (!complete || records.size() >= MAX_RECORDS) {
                break;
            }
            pos += RECORD_HEADER + length;
            if (pos == rawData.length) {
                break;
            }
        }
        // 一条都不完整说明根本不是记录流
        if (records.isEmpty() || !records.get(0).complete()) {
            return List.of();
        }
        return records;
    }

    /**
     * 描述记录结构。
     *
     * @param rawData 载荷
     * @param records 记录
     * @return 描述文本
     */
    protected static String describeRecords(byte[] rawData, List<MmtlsRecord> records) {
        StringBuilder sb = new StringBuilder("[WeChat/MMTLS] records=").append(records.size());
        StringBuilder types = new StringBuilder();
        int appData = 0;
        for (MmtlsRecord record : records) {
            if (!types.isEmpty()) {
                types.append('+');
            }
            types.append(typeName(record.type()));
            if (record.type() == CT_APPDATA) {
                appData += record.length();
            }
        }
        sb.append(", types=").append(types);
        if (appData > 0) {
            sb.append(", appdata=").append(appData).append('B');
        }
        String handshake = handshakeOf(rawData, records);
        if (handshake != null) {
            sb.append(", handshake=").append(handshake);
        }
        sb.append(", bytes=").append(rawData.length);
        if (records.get(records.size() - 1).complete()) {
            sb.append(", payload=encrypted");
        } else {
            sb.append(", truncated=true");
        }
        return sb.toString();
    }

    /**
     * 取第一条握手记录的握手类型。
     *
     * <p>MMTLS 的握手消息与 TLS 同形：{@code HandshakeType(1) | Length(3) | Body}。</p>
     *
     * @param rawData 载荷
     * @param records 记录
     * @return 握手类型名，没有握手记录返回 null
     */
    protected static String handshakeOf(byte[] rawData, List<MmtlsRecord> records) {
        MmtlsRecord first = records.get(0);
        if (first.type() != CT_HANDSHAKE || !first.complete() || rawData.length < RECORD_HEADER + 1) {
            return null;
        }
        int handshakeType = rawData[RECORD_HEADER] & 0xff;
        return switch (handshakeType) {
            case 0x01 -> "ClientHello";
            case 0x02 -> "ServerHello";
            case 0x0b -> "Certificate";
            case 0x0c -> "ServerKeyExchange";
            case 0x0e -> "ServerHelloDone";
            case 0x10 -> "ClientKeyExchange";
            case 0x14 -> "Finished";
            default -> "type=0x" + Integer.toHexString(handshakeType);
        };
    }

    /**
     * 记录类型是否是已知的四种。
     *
     * @param type 类型
     * @return true 表示已知
     */
    private static boolean isKnownType(int type) {
        return type == CT_CHANGE_CIPHER_SPEC || type == CT_ALERT
                || type == CT_HANDSHAKE || type == CT_APPDATA;
    }

    /**
     * 记录类型名。
     *
     * @param type 类型
     * @return 名称
     */
    private static String typeName(int type) {
        return switch (type) {
            case CT_CHANGE_CIPHER_SPEC -> "change_cipher_spec";
            case CT_ALERT -> "alert";
            case CT_HANDSHAKE -> "handshake";
            case CT_APPDATA -> "appdata";
            default -> "type=0x" + Integer.toHexString(type);
        };
    }

    /**
     * 取命中的微信主机名。
     *
     * @param protocolInfo 还原上下文
     * @return 主机名，未命中返回 null
     */
    protected static String host(Map<String, Object> protocolInfo) {
        String sni = AppTrafficDecoder.serverName(protocolInfo);
        if (AppTrafficDecoder.hostMatches(sni, DOMAINS)) {
            return sni;
        }
        String httpHost = AppTrafficDecoder.httpHost(protocolInfo);
        if (AppTrafficDecoder.hostMatches(httpHost, DOMAINS)) {
            return httpHost;
        }
        return null;
    }
}
