// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.network.support.tshark.restorer;

import java.util.List;
import java.util.Map;

/**
 * 主流即时通讯 / 协同应用的连接级还原（钉钉、飞书、Telegram、WhatsApp、Slack…）。
 *
 * <p><b>为什么这些不各写一个类</b>：它们的共同点是「全部跑在 TLS/WSS 上」，
 * 载荷本身是密文，能还原的信息是同构的——应用名、目标主机、载荷规模、
 * 以及极少数未加密帧里的可读字段。差别只有域名表，所以这里用一张表驱动，
 * 新增一个应用只需要在 {@link #APPS} 里加一行，不用改解析逻辑。</p>
 *
 * <p><b>为什么仍然走 SPI</b>：它和其它还原器一样实现 {@code ProtocolRestorer}，
 * 由 {@code META-INF/extensions} 注册、由 {@code PacketParserService} 按优先级调度，
 * 宿主不需要为此改任何代码。</p>
 *
 * <p><b>边界</b>：不解密、不伪造字段。纯密文只给「payload=encrypted」，
 * 这与「抓到了但看不到内容」的运维事实一致。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
public class ImAppProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * 应用表：显示名 + 域名后缀
     */
    private static final List<App> APPS = List.of(
            new App("DingTalk", List.of("dingtalk.com", "dingtalkapps.com", "dingtalk.net")),
            new App("Feishu/Lark", List.of("feishu.cn", "feishu.net", "larksuite.com", "larkoffice.com")),
            new App("Telegram", List.of("telegram.org", "telegram.me", "t.me", "telesco.pe",
                    "telegram-cdn.org", "tdesktop.com")),
            new App("WhatsApp", List.of("whatsapp.net", "whatsapp.com", "wa.me", "fbcdn.net")),
            new App("Slack", List.of("slack.com", "slack-edge.com", "slack-msgs.com", "slackb.com")),
            new App("Signal", List.of("signal.org", "whispersystems.org", "signal.art")),
            new App("Discord", List.of("discord.com", "discordapp.com", "discordapp.net", "discord.gg")),
            new App("LINE", List.of("line.me", "line-apps.com", "line-scdn.net")),
            new App("Instagram", List.of("instagram.com", "cdninstagram.com")),
            new App("WhatsApp Business", List.of("whatsapp.biz"))
    );

    /**
     * 单帧最多报几个可读字段
     */
    private static final int MAX_FIELDS = 6;

    /**
     * 应用描述。
     *
     * @param label   展示名
     * @param domains 域名后缀
     */
    private record App(String label, List<String> domains) {
    }

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "im";
    }

    /**
     * 获取Priority
     *
     * <p>早于 {@link HttpsProtocolRestorer}（20）：认得出应用时，应用名比通用 TLS 记录有用。
     * 晚于微信（15）与 QQ（16），让更专门的还原器先命中。</p>
     */
    @Override
    public int getPriority() {
        return 17;
    }

    /**
     * 是否可以Restore：只按主机名认，避免把任意 TLS 流量都标成 IM。
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        return resolve(protocolInfo) != null;
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        App app = resolve(protocolInfo);
        if (app == null) {
            return "[IM] unknown";
        }
        String host = hostOf(protocolInfo);
        StringBuilder sb = new StringBuilder("[");
        sb.append(app.label()).append("] host=").append(host);
        if (rawData == null || rawData.length == 0) {
            return sb.append(", bytes=0").toString();
        }
        sb.append(", bytes=").append(rawData.length);
        boolean handshake = rawData.length >= 4 && (rawData[0] & 0xff) == 0x16;
        if (handshake) {
            sb.append(", tls=handshake");
        } else {
            AppTrafficDecoder.appendFields(sb, rawData, MAX_FIELDS);
        }
        return sb.toString();
    }

    /**
     * 命中哪个应用。
     *
     * @param protocolInfo 还原上下文
     * @return 应用，未命中返回 null
     */
    private static App resolve(Map<String, Object> protocolInfo) {
        String host = hostOf(protocolInfo);
        if (host.isEmpty()) {
            return null;
        }
        for (App app : APPS) {
            if (AppTrafficDecoder.hostMatches(host, app.domains())) {
                return app;
            }
        }
        return null;
    }

    /**
     * 取 SNI 或 HTTP Host。
     *
     * @param protocolInfo 还原上下文
     * @return 主机名，缺失返回空串
     */
    private static String hostOf(Map<String, Object> protocolInfo) {
        String sni = AppTrafficDecoder.serverName(protocolInfo);
        return sni.isEmpty() ? AppTrafficDecoder.httpHost(protocolInfo) : sni;
    }
}
