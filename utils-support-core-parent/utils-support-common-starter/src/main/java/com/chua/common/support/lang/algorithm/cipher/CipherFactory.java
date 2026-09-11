package com.chua.common.support.lang.algorithm.cipher;


/**
 * 加解密算法统一工厂（门面）。
 *
 * <p>作为所有加解密算法的<b>统一入口</b>，按算法名返回对应实现，再链式调用具体操作。
 * 其中 AES / DES 为 JDK 内置实现（直接实例化，无需提供者）；
 * 其余算法通过 SPI 机制按提供者名称加载（当前内置 BouncyCastle，名称 "bc"）。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 统一入口 + 链式调用
 * byte[] enc = CipherFactory.sm2("bc").encrypt(publicKey, data);   // SM2 非对称
 * byte[] dec = CipherFactory.sm4("bc").decrypt(key, enc);          // SM4 对称
 * byte[] aes = CipherFactory.aes().encrypt(key, data);             // AES 内置
 *
 * // HPKE 混合加密（发送方 / 接收方链式门面）
 * HpkeCipher hpke = CipherFactory.hpke("bc");
 * HpkeCipher.SealedMessage msg = hpke.sender().receiverPk(receiverPub).aad(aad).seal(plain);
 * byte[] out = hpke.receiver().secretKey(receiverPriv).enc(msg.enc()).aad(aad).open(msg.ciphertext());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CipherFactory {

    private CipherFactory() {
    }

    // ==================== JDK 内置（无需提供者） ====================

    /**
     * AES 对称加解密（JDK 内置，AES/CBC/PKCS5Padding）。
     *
     * @return AesCipher 实例
     */
    public static AesCipher aes() {
        return new AesCipher();
    }

    /**
     * DES 对称加解密（JDK 内置）。
     *
     * @return DesCipher 实例
     */
    public static DesCipher des() {
        return new DesCipher();
    }

    // ==================== SPI 算法（按提供者加载） ====================

    /**
     * RSA 非对称加解密 / 签名验签。
     *
     * @param provider 提供者名称，如 "bc"
     * @return RsaCipher 实例
     */
    public static RsaCipher rsa(String provider) {
        return RsaCipher.create(provider);
    }

    /**
     * SM2 非对称加解密 / 签名验签。
     *
     * @param provider 提供者名称，如 "bc"
     * @return Sm2Cipher 实例
     */
    public static Sm2Cipher sm2(String provider) {
        return Sm2Cipher.create(provider);
    }

    /**
     * SM4 对称加解密。
     *
     * @param provider 提供者名称，如 "bc"
     * @return Sm4Cipher 实例
     */
    public static Sm4Cipher sm4(String provider) {
        return Sm4Cipher.create(provider);
    }

    /**
     * ECIES 椭圆曲线集成加密方案。
     *
     * @param provider 提供者名称，如 "bc"
     * @return EciesCipher 实例
     */
    public static EciesCipher ecies(String provider) {
        return EciesCipher.create(provider);
    }

    /**
     * HPKE（RFC 9180）混合公钥加密，支持链式门面调用。
     *
     * @param provider 提供者名称，如 "bc"
     * @return HpkeCipher 实例
     */
    public static HpkeCipher hpke(String provider) {
        return HpkeCipher.create(provider);
    }

    /**
     * Twofish 对称加解密。
     *
     * @param provider 提供者名称，如 "bc"
     * @return TwofishCipher 实例
     */
    public static TwofishCipher twofish(String provider) {
        return TwofishCipher.create(provider);
    }

    /**
     * Noekeon 对称加解密。
     *
     * @param provider 提供者名称，如 "bc"
     * @return NoekeonCipher 实例
     */
    public static NoekeonCipher noekeon(String provider) {
        return NoekeonCipher.create(provider);
    }

    /**
     * 3DES 对称加解密。
     *
     * @param provider 提供者名称，如 "bc"
     * @return DesedeCipher 实例
     */
    public static DesedeCipher desede(String provider) {
        return DesedeCipher.create(provider);
    }
}
