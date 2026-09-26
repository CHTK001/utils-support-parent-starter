package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.EciesCipher;
import com.chua.common.support.spi.annotations.Spi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.KDF2BytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.util.BigIntegers;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;

/**
 * 基于 bouncycastle 的 弹性容器实例 椭圆曲线集成加密方案实现
 *
 * <p>通过 SPI 机制以 "bc" 名称注册，实现密钥封装与对称加密结合的混合加密方案。
 * 使用 P-256 (secp256r1) 曲线，ECDH 密钥协商，SHA-256 KDF，AES/CBC 数据加密。
 *
 * <p>加密输出为 {@code 长度||临时公钥||长度||IV||长度||密文||HMAC-SHA256 标签}。
 * 认证标签由 KDF 单独派生的认证密钥计算（encrypt-then-MAC），解密时先验签再解密：
 * 未认证的 CBC 会让攻击者篡改 IV 或密文后被静默接受，既构成 padding oracle，
 * 也让密文可被位翻转改写。
 *
 * @author CH
 * @since 2026-07-16
 */
@Spi({"bc", "bouncycastle"})
public class BcEciesCipher implements EciesCipher {

    /**
     * 提供者
    */
    private static final String PROVIDER = "BC";
    /**
     * Curve
    */
    private static final String CURVE = "secp256r1";
    /**
     * 键_algorithm
    */
    private static final String KEY_ALGORITHM = "EC";
    /**
     * AES 分组长度，即 IV 长度（字节）
     */
    private static final int IV_LEN = 16;
    /**
     * 派生的加密密钥长度（字节）
     */
    private static final int ENC_KEY_LEN = 32;
    /**
     * 派生的认证密钥长度（字节）
     */
    private static final int MAC_KEY_LEN = 32;
    /**
     * HMAC-SHA256 标签长度（字节）
     */
    private static final int MAC_LEN = 32;
    /**
     * secp256r1 共享秘密的定长字节数
     */
    private static final int SHARED_LEN = 32;
    /**
     * 单个长度前缀的字节数
     */
    private static final int LEN_FIELD = 4;

    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    /**
     * generate键pair
    */
    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(KEY_ALGORITHM, PROVIDER);
            gen.initialize(new ECGenParameterSpec(CURVE), new SecureRandom());
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("ECIES 密钥对生成失败", e);
        }
    }

    @Override
    /**
     * Encrypt
    */
    public byte[] encrypt(byte[] publicKey, byte[] data) {
        try {
            KeyPair ephemeral = generateKeyPair();
            byte[] ephemeralPub = ephemeral.getPublic().getEncoded();
            byte[] shared = agree(PrivateKeyFactory.createKey(ephemeral.getPrivate().getEncoded()),
                    PublicKeyFactory.createKey(publicKey));
            byte[][] keys = deriveKeys(shared);

            CBCBlockCipher cbc = new CBCBlockCipher(new AESEngine());
            PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(cbc, new PKCS7Padding());
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            cipher.init(true, new ParametersWithIV(new KeyParameter(keys[0]), iv));
            byte[] enc = new byte[cipher.getOutputSize(data.length)];
            int len = cipher.processBytes(data, 0, data.length, enc, 0);
            len += cipher.doFinal(enc, len);

            byte[] body = concat(lengthPrefix(ephemeralPub.length), ephemeralPub,
                    lengthPrefix(IV_LEN), iv, lengthPrefix(len), enc);
            return concat(body, hmac(keys[1], body));
        } catch (Exception e) {
            throw new RuntimeException("ECIES 加密失败", e);
        }
    }

    @Override
    /**
     * Decrypt
    */
    public byte[] decrypt(byte[] privateKey, byte[] ciphertext) {
        try {
            int off = 0;
            int eLen = readLength(ciphertext, off);
            off += LEN_FIELD;
            requireRemaining(ciphertext, off, eLen + LEN_FIELD + IV_LEN + LEN_FIELD + MAC_LEN);
            byte[] ephemeralPub = new byte[eLen];
            System.arraycopy(ciphertext, off, ephemeralPub, 0, eLen);
            off += eLen;
            int ivLen = readLength(ciphertext, off);
            off += LEN_FIELD;
            if (ivLen != IV_LEN) {
                throw new IllegalArgumentException("ECIES 信封 IV 长度非法: " + ivLen);
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(ciphertext, off, iv, 0, IV_LEN);
            off += IV_LEN;
            int ctLen = readLength(ciphertext, off);
            off += LEN_FIELD;
            requireRemaining(ciphertext, off, ctLen + MAC_LEN);
            byte[] enc = new byte[ctLen];
            System.arraycopy(ciphertext, off, enc, 0, ctLen);

            byte[] body = Arrays.copyOf(ciphertext, ciphertext.length - MAC_LEN);
            byte[] tag = Arrays.copyOfRange(ciphertext, ciphertext.length - MAC_LEN, ciphertext.length);
            byte[] shared = agree(PrivateKeyFactory.createKey(privateKey),
                    PublicKeyFactory.createKey(ephemeralPub));
            byte[][] keys = deriveKeys(shared);
            if (!MessageDigest.isEqual(hmac(keys[1], body), tag)) {
                throw new IllegalArgumentException("ECIES 认证标签校验失败，密文已被篡改");
            }

            CBCBlockCipher cbc = new CBCBlockCipher(new AESEngine());
            PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(cbc, new PKCS7Padding());
            cipher.init(false, new ParametersWithIV(new KeyParameter(keys[0]), iv));
            byte[] dec = new byte[cipher.getOutputSize(ctLen)];
            int len = cipher.processBytes(enc, 0, ctLen, dec, 0);
            len += cipher.doFinal(dec, len);
            return Arrays.copyOf(dec, len);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("ECIES 解密失败", e);
        }
    }

    /**
     * ECDH 协商，按 SEC1 把共享秘密编码为定长大端字节串。
     *
     * <p>{@code BigInteger.toByteArray()} 会因符号位补零或丢失前导零而变长，
     * 直接喂给 KDF 会让密钥材料随曲线点取值漂移，故统一补齐到曲线字段长度。</p>
     *
     * @param priv 本地私钥
     * @param pub 对端公钥
     * @return 定长共享秘密
     */
    private static byte[] agree(AsymmetricKeyParameter priv, AsymmetricKeyParameter pub) {
        ECDHBasicAgreement agreement = new ECDHBasicAgreement();
        agreement.init(priv);
        return BigIntegers.asUnsignedByteArray(SHARED_LEN, agreement.calculateAgreement(pub));
    }

    /**
     * 由共享秘密派生加密钥与认证钥。
     *
     * @param shared 共享秘密
     * @return 长度 2 的数组：{@code [0]} 加密钥，{@code [1]} 认证钥
     */
    private static byte[][] deriveKeys(byte[] shared) {
        KDF2BytesGenerator kdf = new KDF2BytesGenerator(new SHA256Digest());
        kdf.init(new KDFParameters(shared, new byte[0]));
        byte[] okm = new byte[ENC_KEY_LEN + MAC_KEY_LEN];
        kdf.generateBytes(okm, 0, okm.length);
        return new byte[][]{Arrays.copyOfRange(okm, 0, ENC_KEY_LEN),
                Arrays.copyOfRange(okm, ENC_KEY_LEN, okm.length)};
    }

    /**
     * 计算 HMAC-SHA256 认证标签。
     *
     * @param key 认证钥
     * @param body 待认证数据
     * @return 认证标签
     */
    private static byte[] hmac(byte[] key, byte[] body) {
        HMac mac = new HMac(new SHA256Digest());
        mac.init(new KeyParameter(key));
        mac.update(body, 0, body.length);
        byte[] tag = new byte[mac.getMacSize()];
        mac.doFinal(tag, 0);
        return tag;
    }

    /**
     * 写出 4 字节大端长度前缀。
     *
     * @param length 数据长度
     * @return 长度前缀
     */
    private static byte[] lengthPrefix(int length) {
        return new byte[]{(byte) (length >> 24), (byte) (length >> 16), (byte) (length >> 8), (byte) length};
    }

    /**
     * 读取 4 字节大端长度前缀。
     *
     * @param src 源数组
     * @param off 偏移
     * @return 长度值
     */
    private static int readLength(byte[] src, int off) {
        requireRemaining(src, off, LEN_FIELD);
        return ((src[off] & 0xff) << 24)
                | ((src[off + 1] & 0xff) << 16)
                | ((src[off + 2] & 0xff) << 8)
                | (src[off + 3] & 0xff);
    }

    /**
     * 校验剩余字节足够，避免畸形信封触发越界。
     *
     * @param src 源数组
     * @param off 当前偏移
     * @param need 需要读取的字节数
     */
    private static void requireRemaining(byte[] src, int off, int need) {
        if (src == null || off < 0 || need < 0 || src.length - off < need) {
            throw new IllegalArgumentException("ECIES 信封不完整");
        }
    }

    /**
     * 拼接多个字节数组。
     *
     * @param parts 片段
     * @return 拼接结果
     */
    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int off = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, off, part.length);
            off += part.length;
        }
        return result;
    }
}
