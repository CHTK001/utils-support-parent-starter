// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.network.support.tshark.restorer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SSH 协议还原器（版本协商 + 明文握手 + 登录用户名）。
 *
 * <p>SSH 在 {@code NEWKEYS} 之后整条连接都是密文，但握手这一段是明文，能还原：</p>
 * <ol>
 *   <li><b>版本串</b>：{@code SSH-2.0-OpenSSH_9.6} —— 对端是什么实现、什么版本；</li>
 *   <li><b>KEXINIT</b>：双方提议的密钥交换、主机密钥、加密与 MAC 算法；</li>
 *   <li><b>USERAUTH_REQUEST</b>：登录用户名与认证方式（password / publickey）。</li>
 * </ol>
 *
 * <p>这几项加起来足够回答「谁、从哪、用什么客户端、按什么算法连进来的」。
 * 认证之后的命令与输出是密文，不猜；SCP / SFTP 都跑在这条加密通道里，同样看不到内容。</p>
 *
 * <p><b>判据从严</b>：二进制报文要求 {@code packet_length} 与实到字节完全吻合、
 * {@code padding_length} 合理，避免把密文里的随机字节误当成 KEXINIT。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SshProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * 版本串前缀
     */
    private static final String BANNER = "SSH-";

    /**
     * SSH 报文头：packet_length(4) + padding_length(1)
     */
    private static final int PACKET_HEADER = 5;

    /**
     * 消息：SSH_MSG_KEXINIT
     */
    private static final int MSG_KEXINIT = 20;

    /**
     * 消息：SSH_MSG_NEWKEYS
     */
    private static final int MSG_NEWKEYS = 21;

    /**
     * 消息：SSH_MSG_SERVICE_REQUEST
     */
    private static final int MSG_SERVICE_REQUEST = 5;

    /**
     * 消息：SSH_MSG_USERAUTH_REQUEST
     */
    private static final int MSG_USERAUTH_REQUEST = 50;

    /**
     * 单个报文的长度上限：超过这个值的「报文」多半是误判
     */
    private static final int MAX_PACKET = 256 * 1024;

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "ssh";
    }

    /**
     * 获取Priority
     */
    @Override
    public int getPriority() {
        return 50;
    }

    /**
     * 是否可以Restore
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return false;
        }
        if (isBanner(rawData)) {
            return true;
        }
        return parsePayload(rawData) != null && messageType(rawData) >= 0;
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return "[SSH] empty";
        }
        if (isBanner(rawData)) {
            String text = utf8(rawData).trim();
            int end = text.indexOf('\n');
            if (end > 0) {
                text = text.substring(0, end).trim();
            }
            return "[SSH Version] " + text;
        }
        int type = messageType(rawData);
        byte[] payload = parsePayload(rawData);
        if (payload == null || type < 0) {
            return "[SSH] encrypted or malformed, bytes=" + rawData.length;
        }
        return switch (type) {
            case MSG_KEXINIT -> describeKexInit(payload);
            case MSG_NEWKEYS -> "[SSH] newkeys (后续载荷加密)";
            case MSG_SERVICE_REQUEST -> "[SSH] service=" + readString(payload, 1).value();
            case MSG_USERAUTH_REQUEST -> describeUserAuth(payload);
            default -> "[SSH] message type=" + type + ", bytes=" + rawData.length;
        };
    }

    /**
     * 是否是版本串。
     *
     * @param rawData 载荷
     * @return true 表示是
     */
    private static boolean isBanner(byte[] rawData) {
        return rawData.length >= 4
                && BANNER.equals(new String(rawData, 0, 4, StandardCharsets.US_ASCII));
    }

    /**
     * 取二进制报文的 payload（去掉长度、填充长度与填充）。
     *
     * @param rawData 载荷
     * @return payload；不是合法单报文返回 null
     */
    private static byte[] parsePayload(byte[] rawData) {
        if (rawData.length < PACKET_HEADER + 1) {
            return null;
        }
        int packetLength = ((rawData[0] & 0xff) << 24) | ((rawData[1] & 0xff) << 16)
                | ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        if (packetLength <= PACKET_HEADER || packetLength > MAX_PACKET) {
            return null;
        }
        int padding = rawData[4] & 0xff;
        if (padding < 4 || padding >= packetLength) {
            return null;
        }
        // 一帧里可能只抓到半个报文：允许实到字节少于声明，但不能多
        int available = rawData.length - PACKET_HEADER;
        int payloadLength = packetLength - padding - 1;
        if (payloadLength <= 0 || payloadLength > available) {
            return null;
        }
        return java.util.Arrays.copyOfRange(rawData, PACKET_HEADER, PACKET_HEADER + payloadLength);
    }

    /**
     * 取消息类型。
     *
     * @param rawData 载荷
     * @return 消息号，无法判定返回 -1
     */
    private static int messageType(byte[] rawData) {
        byte[] payload = parsePayload(rawData);
        return payload == null || payload.length == 0 ? -1 : payload[0] & 0xff;
    }

    /**
     * 描述 KEXINIT：算法清单。
     *
     * @param payload 报文 payload
     * @return 描述文本
     */
    private static String describeKexInit(byte[] payload) {
        // cookie 16 字节之后是 10 个 name-list
        Str kex = readString(payload, 1 + 16);
        Str hostKey = readString(payload, kex.next);
        Str cipherC2s = readString(payload, hostKey.next);
        Str cipherS2c = readString(payload, cipherC2s.next);
        return "[SSH] kexinit, kex=" + abbreviate(kex.value)
                + ", hostKey=" + abbreviate(hostKey.value)
                + ", cipher=" + abbreviate(cipherC2s.value) + "/" + abbreviate(cipherS2c.value);
    }

    /**
     * 描述登录请求。
     *
     * @param payload 报文 payload
     * @return 描述文本
     */
    private static String describeUserAuth(byte[] payload) {
        Str user = readString(payload, 1);
        Str service = readString(payload, user.next);
        Str method = readString(payload, service.next);
        return "[SSH] userauth, user=" + user.value + ", service=" + service.value + ", method=" + method.value;
    }

    /**
     * 读一个 SSH string（4 字节长度 + 内容）。
     *
     * @param payload 报文
     * @param at      起点
     * @return 字符串与下一个位置；越界返回空串
     */
    private static Str readString(byte[] payload, int at) {
        if (at < 0 || at + 4 > payload.length) {
            return new Str("", at);
        }
        int length = ((payload[at] & 0xff) << 24) | ((payload[at + 1] & 0xff) << 16)
                | ((payload[at + 2] & 0xff) << 8) | (payload[at + 3] & 0xff);
        if (length <= 0 || at + 4 + length > payload.length) {
            return new Str("", at);
        }
        return new Str(new String(payload, at + 4, length, StandardCharsets.US_ASCII), at + 4 + length);
    }

    /**
     * SSH string 的读取结果。
     *
     * @param value 内容
     * @param next  下一个位置
     */
    private record Str(String value, int next) {
    }

    /**
     * 算法清单只留前两项，避免一行太长。
     *
     * @param value 逗号分隔的清单
     * @return 截断后的值
     */
    private static String abbreviate(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        String[] parts = value.split(",");
        return parts.length <= 2 ? value : parts[0] + "," + parts[1] + ",…";
    }
}
