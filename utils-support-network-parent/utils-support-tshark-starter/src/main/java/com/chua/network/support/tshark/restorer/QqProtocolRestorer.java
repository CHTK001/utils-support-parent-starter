// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.network.support.tshark.restorer;

import java.util.List;
import java.util.Map;

/**
 * QQ 协议还原器（OICQ 头 + TLS 连接识别 + 明文字段提取）。
 *
 * <p>与微信同构：能识别到「这是 QQ 的连接」，能解出 OICQ 头（版本/命令/序号/QQ 号），
 * 明文 protobuf 载荷能取出可读字段；登录完成后的业务载荷是密文，只报长度与
 * 「已加密」，不编造内容。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class QqProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * QQ 常用域名后缀（含图片/文件 CDN）
     */
    private static final List<String> DOMAINS = List.of(
            "qq.com", "gtimg.com", "gtimg.cn", "qpic.cn", "idqqimg.com", "tencent.com");

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "qq";
    }

    /**
     * 获取Priority
     *
     * <p>早于 {@link HttpsProtocolRestorer}（20），应用层标签优先于通用 TLS 记录。</p>
     */
    @Override
    public int getPriority() {
        return 16;
    }

    /**
     * 是否可以Restore
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (host(protocolInfo) != null) {
            return true;
        }
        return matchesOicq(rawData);
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[QQ] empty";
        }
        String host = host(protocolInfo);
        if (host != null) {
            StringBuilder sb = new StringBuilder("[QQ] host=").append(host);
            sb.append(", bytes=").append(rawData.length);
            boolean handshake = rawData.length >= 4 && (rawData[0] & 0xff) == 0x16;
            if (handshake) {
                sb.append(", tls=handshake");
            }
            if (!handshake) {
                AppTrafficDecoder.appendFields(sb, rawData, 6);
            }
            return sb.toString();
        }
        if (matchesOicq(rawData)) {
            return describeOicq(rawData);
        }
        return "[QQ] bytes=" + rawData.length;
    }

    /**
     * 取命中的 QQ 主机名。
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

    /**
     * 是否是 OICQ 头。
     *
     * @param rawData 载荷
     * @return true 表示命中
     */
    protected static boolean matchesOicq(byte[] rawData) {
        if (rawData == null || rawData.length < 7) {
            return false;
        }
        int version = rawData[0] & 0xff;
        if (version != 0x02 && version != 0x03) {
            return false;
        }
        int command = ((rawData[1] & 0xff) << 8) | (rawData[2] & 0xff);
        return command >= 0x0001 && command <= 0x00ff;
    }

    /**
     * 描述 OICQ 头。
     *
     * @param rawData 载荷
     * @return 描述文本
     */
    protected static String describeOicq(byte[] rawData) {
        int version = rawData[0] & 0xff;
        int command = ((rawData[1] & 0xff) << 8) | (rawData[2] & 0xff);
        int seq = ((rawData[3] & 0xff) << 8) | (rawData[4] & 0xff);
        long qq = rawData.length >= 11
                ? (((long) (rawData[5] & 0xff) << 24)
                | ((long) (rawData[6] & 0xff) << 16)
                | ((long) (rawData[7] & 0xff) << 8)
                | (rawData[8] & 0xff))
                : 0L;

        StringBuilder sb = new StringBuilder("[QQ/OICQ] ");
        sb.append("version=").append(version);
        sb.append(", command=").append(command).append(" (").append(toCommandName(command)).append(')');
        sb.append(", seq=").append(seq);
        if (qq > 10000) {
            sb.append(", qq=").append(qq);
        }
        List<String> strings = AppTrafficDecoder.strings(rawData, 4);
        if (!strings.isEmpty()) {
            sb.append(", fields=").append(AppTrafficDecoder.join(strings));
        }
        return sb.toString();
    }

    /**
     * 转为命令名称
     *
     * @param command 命令
     * @return 转为命令名称的结果
     */
    private static String toCommandName(int command) {
        return switch (command) {
            case 0x0001 -> "LoginConfirm";
            case 0x0002 -> "LoginReject";
            case 0x0006 -> "LoginRequest";
            case 0x0007 -> "Logout";
            case 0x0008 -> "KeepAlive";
            case 0x0009 -> "UpdateInfo";
            case 0x000a -> "SearchUser";
            case 0x000b -> "GetBuddyList";
            case 0x000c -> "GetOnlineBuddy";
            case 0x000d -> "SendMsg";
            case 0x000e -> "RecvMsg";
            case 0x000f -> "GetMsg";
            case 0x001a -> "ChangeStatus";
            case 0x001d -> "RequestKey";
            case 0x001e -> "LoginRequestEx";
            case 0x001f -> "LoginConfirmEx";
            case 0x0020 -> "ExSearchUser";
            default -> "UnknownCommand";
        };
    }
}
