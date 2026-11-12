// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.network.support.tshark.restorer;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * RDP 协议还原器（TPKT / X.224 连接协商 + 协商结果）。
 *
 * <p>RDP 在进入加密通道之前有一段明文协商，这一段能还原出很有用的三件事：</p>
 * <ol>
 *   <li><b>登录用的 Cookie</b>：{@code Cookie: mstshash=<用户名>} —— 谁在连；</li>
 *   <li><b>请求的安全协议</b>：RDP Negotiation Request 里的 4 字节位图
 *       （SSL / HYBRID / HYBRID_EX），决定后面是不是 TLS；</li>
 *   <li><b>协商应答</b>：CC（Connection Confirm）表示握手受理。</li>
 * </ol>
 *
 * <p>之后进入 TLS / CredSSP，载荷是密文，和 HTTPS 一样只能到结构级；这里不猜内容。</p>
 *
 * <p><b>为什么不用 tshark 的 rdp 层直接取字段</b>：老版本 tshark 对带 Cookie 的
 * 连接请求只报「Unknown」，而这段明文恰恰是排障时最需要的（连的哪台、用了哪个账号、
 * 协商成什么安全层）。这里按 TPKT + X.224 的固定布局自己解，和 tshark 版本无关。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
public class RdpProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * TPKT 头长度：version(1) + reserved(1) + length(2)
     */
    private static final int TPKT_HEADER = 4;

    /**
     * X.224 CR（Connection Request）的 TPDU 码
     */
    private static final int X224_CR = 0xE0;

    /**
     * X.224 CC（Connection Confirm）的 TPDU 码
     */
    private static final int X224_CC = 0xD0;

    /**
     * RDP 协商请求的固定前缀：type=1(REQUEST) + flags=0 + length=8(LE)
     */
    private static final byte[] NEGO_REQUEST = {0x01, 0x00, 0x08, 0x00};

    /**
     * Cookie 前缀
     */
    private static final String COOKIE_PREFIX = "Cookie: mstshash=";

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "rdp";
    }

    /**
     * 获取Priority
     */
    @Override
    public int getPriority() {
        return 45;
    }

    /**
     * 是否可以Restore
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < TPKT_HEADER + 2) {
            return false;
        }
        if ((rawData[0] & 0xff) != 0x03 || (rawData[1] & 0xff) != 0x00) {
            return false;
        }
        int declared = ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        if (declared < TPKT_HEADER + 2 || declared > rawData.length) {
            // 抓包截断时 declared 可能大于实到字节，只要还有 X.224 头就继续
            if (rawData.length < TPKT_HEADER + 7) {
                return false;
            }
        }
        int tpdu = rawData[TPKT_HEADER + 1] & 0xff;
        if (tpdu == X224_CR || tpdu == X224_CC) {
            return true;
        }
        // 少数客户端把 Cookie 放在 X.224 头之前，TPDU 位置会错开：按 Cookie 兜底
        return indexOf(rawData, COOKIE_PREFIX.getBytes(StandardCharsets.US_ASCII)) >= 0;
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < TPKT_HEADER + 2) {
            return "[RDP] empty";
        }
        int declared = ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        int bodyEnd = Math.min(rawData.length, Math.max(TPKT_HEADER + 2, declared));
        String body = new String(rawData, TPKT_HEADER, bodyEnd - TPKT_HEADER, StandardCharsets.ISO_8859_1);

        StringBuilder sb = new StringBuilder("[RDP] ");
        int tpdu = rawData[TPKT_HEADER + 1] & 0xff;
        if (tpdu == X224_CR) {
            sb.append("connection request");
        } else if (tpdu == X224_CC) {
            sb.append("connection confirm");
        } else {
            sb.append("tpkt");
        }
        int cookieAt = body.indexOf(COOKIE_PREFIX);
        if (cookieAt >= 0) {
            int end = body.indexOf('\r', cookieAt);
            String cookie = end < 0 ? body.substring(cookieAt) : body.substring(cookieAt, end);
            sb.append(", cookie=").append(cookie.substring(COOKIE_PREFIX.length()).trim());
        }
        int protocols = requestedProtocols(rawData);
        if (protocols >= 0) {
            sb.append(", protocols=").append(protocolNames(protocols));
        }
        sb.append(", bytes=").append(rawData.length);
        return sb.toString();
    }

    /**
     * 取 RDP Negotiation Request 里的 requestedProtocols（小端）。
     *
     * @param rawData 载荷
     * @return 位图；未找到返回 -1
     */
    protected static int requestedProtocols(byte[] rawData) {
        int at = indexOf(rawData, NEGO_REQUEST);
        if (at < 0 || at + NEGO_REQUEST.length + 4 > rawData.length) {
            return -1;
        }
        int base = at + NEGO_REQUEST.length;
        return (rawData[base] & 0xff)
                | ((rawData[base + 1] & 0xff) << 8)
                | ((rawData[base + 2] & 0xff) << 16)
                | ((rawData[base + 3] & 0xff) << 24);
    }

    /**
     * 位图转可读协议名。
     *
     * @param protocols 位图
     * @return 形如 {@code SSL|HYBRID}
     */
    protected static String protocolNames(int protocols) {
        if (protocols <= 0) {
            return "STANDARD_RDP";
        }
        StringBuilder sb = new StringBuilder();
        appendFlag(sb, protocols, 0x00000001, "SSL");
        appendFlag(sb, protocols, 0x00000002, "HYBRID");
        appendFlag(sb, protocols, 0x00000004, "RDSTLS");
        appendFlag(sb, protocols, 0x00000008, "HYBRID_EX");
        return sb.isEmpty() ? "0x" + Integer.toHexString(protocols).toUpperCase(Locale.ROOT) : sb.toString();
    }

    /**
     * 追加一个标志位。
     *
     * @param sb        输出
     * @param value     位图
     * @param flag      位
     * @param name      名称
     */
    private static void appendFlag(StringBuilder sb, int value, int flag, String name) {
        if ((value & flag) == 0) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('|');
        }
        sb.append(name);
    }

    /**
     * 在字节数组里找子串。
     *
     * @param data 数据
     * @param key  子串
     * @return 下标，未找到返回 -1
     */
    private static int indexOf(byte[] data, byte[] key) {
        if (data == null || key == null || key.length == 0 || data.length < key.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= data.length - key.length; i++) {
            for (int j = 0; j < key.length; j++) {
                if (data[i + j] != key[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
