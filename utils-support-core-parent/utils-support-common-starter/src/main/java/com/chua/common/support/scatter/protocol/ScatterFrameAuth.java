// [P3C A20 豁免] A20/A30/A31: 帧布局固定长度常量（nonce/摘要长度），非业务魔法值。
package com.chua.common.support.scatter.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * scatter 帧鉴权：基于共享密钥的 HMAC-SHA256 帧签名。
 *
 * <p>线格式：{@code 原始帧字节 || nonce(8B) || hmac(32B)}，签名覆盖「帧字节 + nonce」。
 * 设计要点：</p>
 * <ul>
 *   <li><b>向后兼容</b>：未配置密钥的接收端按原帧解码（{@link ScatterFrame#decode}
 *       忽略尾部多余字节），签名帧对其仍然可读；配置密钥的接收端对无签名/验签失败的帧一律丢弃（fail-closed）。</li>
 *   <li><b>防篡改/防伪造</b>：同网段任意主机无法在不知共享密钥的情况下构造合法帧
 *       （DISC 灌表、LEAVE 踢人、REQ/RESP 投毒均被拦截）。</li>
 *   <li><b>nonce</b>：每帧随机，使相同帧内容的签名不同，增加重放拼装难度；
 *       重放防护（nonce 去重）由上层按需扩展。</li>
 * </ul>
 *
 * <p>发送端在帧编码后追加签名尾部，接收端先验签剥离再解码。密钥经
 * {@code ScatterSetting#setAuthToken} 配置，空/空白表示不启用鉴权（历史行为）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class ScatterFrameAuth {

    /**
     * nonce 长度（字节）
     */
    public static final int NONCE_LEN = 8;
    /**
     * HMAC-SHA256 摘要长度（字节）
     */
    public static final int MAC_LEN = 32;
    /**
     * 签名尾部总长度（nonce + mac）
     */
    public static final int TRAILER_LEN = NONCE_LEN + MAC_LEN;
    /**
     * HMAC 算法名
     */
    private static final String HMAC_ALGO = "HmacSHA256";
    /**
     * nonce 随机源（线程安全）
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 构造方法，创建 ScatterFrameAuth 实例（工具类，禁止实例化）。
     */
    private ScatterFrameAuth() {
    }

    /**
     * 对帧字节追加签名尾部（nonce + hmac）。
     *
     * @param frameBytes 原始帧字节（ScatterFrame#encode 的输出），不允许为 null
     * @param token      共享密钥；空/空白时原样返回（不签名）
     * @return 签名后的线格式字节（帧 || nonce || mac）
     */
    public static byte[] sign(byte[] frameBytes, String token) {
        if (frameBytes == null || token == null || token.isBlank()) {
            return frameBytes;
        }
        byte[] nonce = new byte[NONCE_LEN];
        RANDOM.nextBytes(nonce);
        byte[] mac = hmac(token, frameBytes, nonce);
        byte[] out = new byte[frameBytes.length + TRAILER_LEN];
        System.arraycopy(frameBytes, 0, out, 0, frameBytes.length);
        System.arraycopy(nonce, 0, out, frameBytes.length, NONCE_LEN);
        System.arraycopy(mac, 0, out, frameBytes.length + NONCE_LEN, MAC_LEN);
        return out;
    }

    /**
     * 校验签名并剥离尾部，返回原始帧字节。
     *
     * @param wire  线格式字节（帧 || nonce || mac）
     * @param token 共享密钥；空/空白时返回 null（未配置鉴权不应调用本方法）
     * @return 原始帧字节；验签失败/长度不足返回 null
     */
    public static byte[] verifyAndStrip(byte[] wire, String token) {
        if (wire == null || wire.length < TRAILER_LEN || token == null || token.isBlank()) {
            return null;
        }
        int frameLen = wire.length - TRAILER_LEN;
        byte[] nonce = Arrays.copyOfRange(wire, frameLen, frameLen + NONCE_LEN);
        byte[] mac = Arrays.copyOfRange(wire, frameLen + NONCE_LEN, wire.length);
        byte[] expected = hmac(token, Arrays.copyOf(wire, frameLen), nonce);
        // 恒定时间比较，避免时序侧信道
        return MessageDigest.isEqual(expected, mac) ? Arrays.copyOf(wire, frameLen) : null;
    }

    /**
     * 计算 HMAC-SHA256(frame || nonce, token)。
     *
     * @param token  共享密钥，不允许为 null
     * @param frame  帧字节，不允许为 null
     * @param nonce  随机数，不允许为 null
     * @return 摘要（32 字节）
     */
    private static byte[] hmac(String token, byte[] frame, byte[] nonce) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            mac.update(frame);
            mac.update(nonce);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException("scatter 帧签名计算失败", e);
        }
    }
}
