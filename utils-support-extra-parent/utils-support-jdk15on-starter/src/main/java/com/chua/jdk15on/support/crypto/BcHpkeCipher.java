package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.HpkeCipher;
import com.chua.common.support.spi.annotations.Spi;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 基于 bouncycastle 原语的 HPKE（RFC 9180）混合公钥加密实现。
 *
 * <p>通过 SPI 机制以 "bc" 名称注册。由于 BouncyCastle 无现成 HPKE API，本实现用其原语
 * 手工拼装 RFC 9180 基础 模式（无 PSK）：
 * <ul>
 *   <li><b>KEM（密钥封装）</b>：X25519 椭圆曲线 Diffie-Hellman 密钥协商</li>
 *   <li><b>KDF（密钥派生）</b>：HKDF-SHA256（RFC 5869），由共享密钥派生对称密钥与 nonce</li>
 *   <li><b>AEAD（认证加密）</b>：AES-256-GCM（GCM 自带 16 字节认证标签）</li>
 * </ul>
 *
 * <p>nonce 由 KDF 确定性派生（而非随机），因此发送方与接收方无需额外传输 nonce，
 * 只需随密文传输 32 字节的封装密钥 {@code enc} 即可。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 链式门面（HpkeFlow）
 * byte[][] keys = HpkeFlow.of().keys();
 *
 * HpkeFlow.SealedMessage msg = HpkeFlow.of()
 *         .receiverPk(keys[0])
 *         .aad("order-1024".getBytes())
 *         .seal("机密数据".getBytes());
 *
 * byte[] plain = HpkeFlow.of()
 *         .secretKey(keys[1])
 *         .enc(msg.enc())
 *         .aad("order-1024".getBytes())
 *         .open(msg.ciphertext());
 * }</pre>     .aad("order-1024".getBytes())
 *         .open(msg.ciphertext());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"bc", "bouncycastle"})
public class BcHpkeCipher implements HpkeCipher {

    /** X25519 共享密钥长度（字节） */
    private static final int KEY_SIZE = 32;
    /** AES-256 对称密钥长度（字节） */
    private static final int SYM_KEY_LEN = 32;
    /** GCM 认证标签长度（字节） */
    private static final int GCM_TAG_LEN = 16;
    /** GCM nonce 长度（字节） */
    private static final int GCM_NONCE_LEN = 12;
    /** HKDF 盐（域分隔，防止跨协议复用） */
    private static final byte[] SALT = "chua-hpke-salt-v1".getBytes(StandardCharsets.UTF_8);
    /** HKDF 信息上下文（标识本派生用途） */
    private static final byte[] INFO = "chua-hpke-key".getBytes(StandardCharsets.UTF_8);

    /** 安全随机数源，用于生成临时私钥 */
    private final SecureRandom random = new SecureRandom();

    @Override
    /** generate键pair */
    public byte[][] generateKeyPair() {
        byte[] seed = new byte[KEY_SIZE];
        random.nextBytes(seed);
        X25519PrivateKeyParameters sk = new X25519PrivateKeyParameters(seed);
        X25519PublicKeyParameters pk = sk.generatePublicKey();
        return new byte[][]{pk.getEncoded(), sk.getEncoded()};
    }

    @Override
    /** Encap */
    public byte[][] encap(byte[] receiverPublicKey, byte[] ikm) {
        byte[] seed = new byte[KEY_SIZE];
        random.nextBytes(seed);
        X25519PrivateKeyParameters eSk = new X25519PrivateKeyParameters(seed);
        X25519PublicKeyParameters ePk = eSk.generatePublicKey();
        byte[] ek = deriveSharedKey(eSk, receiverPublicKey, ikm);
        return new byte[][]{ePk.getEncoded(), ek};
    }

    @Override
    /** recover键 */
    public byte[] recoverKey(byte[] receiverPrivateKey, byte[] enc, byte[] ikm) {
        X25519PrivateKeyParameters sk = new X25519PrivateKeyParameters(receiverPrivateKey);
        return deriveSharedKey(sk, enc, ikm);
    }

    @Override
    /** Seal */
    public byte[] seal(byte[] ek, byte[] aad, byte[] plaintext) {
        try {
            GCMBlockCipher gcm = new GCMBlockCipher(new AESEngine());
            gcm.init(true, new ParametersWithIV(new KeyParameter(aesKey(ek)), nonce(ek)));
            if (aad != null && aad.length > 0) {
                gcm.processAADBytes(aad, 0, aad.length);
            }
            byte[] out = new byte[gcm.getOutputSize(plaintext.length)];
            int len = gcm.processBytes(plaintext, 0, plaintext.length, out, 0);
            len += gcm.doFinal(out, len);
            return Arrays.copyOf(out, len);
        } catch (Exception e) {
            throw new RuntimeException("HPKE 加密失败", e);
        }
    }

    @Override
    /** 打开 */
    public byte[] open(byte[] ek, byte[] aad, byte[] ciphertext) {
        try {
            GCMBlockCipher gcm = new GCMBlockCipher(new AESEngine());
            gcm.init(false, new ParametersWithIV(new KeyParameter(aesKey(ek)), nonce(ek)));
            if (aad != null && aad.length > 0) {
                gcm.processAADBytes(aad, 0, aad.length);
            }
            byte[] out = new byte[ciphertext.length];
            int len = gcm.processBytes(ciphertext, 0, ciphertext.length, out, 0);
            len += gcm.doFinal(out, len);
            return Arrays.copyOf(out, len);
        } catch (Exception e) {
            throw new RuntimeException("HPKE 解密失败", e);
        }
    }

    /**
     * 取对称密钥（ek 前 32 字节）
     *
     * @param ek ek
     * @return aes键的结果
     */
    private byte[] aesKey(byte[] ek) {
        return Arrays.copyOfRange(ek, 0, SYM_KEY_LEN);
    }

    /**
     * 取 nonce（ek 第 32~44 字节）
     *
     * @param ek ek
     * @return nonce的结果
     */
    private byte[] nonce(byte[] ek) {
        return Arrays.copyOfRange(ek, SYM_KEY_LEN, SYM_KEY_LEN + GCM_NONCE_LEN);
    }

    /**
     * 由 X25519 共享密钥与可选 ikm 经 HKDF 派生对称密钥材料（键||nonce）。
     *
     * @param sk 本地 X25519 私钥
     * @param peerPub 对端 X25519 公钥
     * @param ikm 可选输入密钥材料
     * @return 长度 {@code SYM_KEY_LEN + GCM_NONCE_LEN} 的密钥材料
     */
    private byte[] deriveSharedKey(X25519PrivateKeyParameters sk, byte[] peerPub, byte[] ikm) {
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(sk);
        byte[] shared = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(new X25519PublicKeyParameters(peerPub), shared, 0);
        byte[] ikmInput = ikm == null ? shared : concat(shared, ikm);
        byte[] prk = hkdfExtract(SALT, ikmInput);
        return hkdfExpand(prk, INFO, SYM_KEY_LEN + GCM_NONCE_LEN);
    }

    /**
     * HKDF-Extract（RFC 5869），salt 为空时用全零
     *
     * @param salt salt
     * @param ikm ikm
     * @return hkdfExtract的结果
     */
    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        return hmacSha256(salt.length == 0 ? new byte[32] : salt, ikm);
    }

    /**
     * HKDF-Expand（RFC 5869）
     *
     * @param prk prk
     * @param info 信息
     * @param length 长度
     * @return hkdfExpand的结果
     */
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int off = 0;
        for (int i = 1; off < length; i++) {
            t = hmacSha256(prk, concat(concat(t, info), new byte[]{(byte) i}));
            int n = Math.min(length - off, 32);
            System.arraycopy(t, 0, okm, off, n);
            off += n;
        }
        return okm;
    }

    /**
     * HMAC-SHA256
     *
     * @param key 键
     * @param data 数据
     * @return hmacSha256的结果
     */
    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HPKE 密钥派生失败", e);
        }
    }

    /**
     * 拼接两个字节数组
     *
     * @param a a
     * @param b b
     * @return 连接的结果
     */
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /**
     * GCM 标签长度（供调用方核对密文结构时使用）
     *
     * @return gcm标签长度的结果
     */
    public int gcmTagLength() {
        return GCM_TAG_LEN;
    }
}
