package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.ServiceProvider;

import java.security.KeyPair;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * RSA 非对称加解密 SPI 接口
 *
 * <p>基于 SPI 机制加载实现，支持 RSA 算法的密钥生成、加密、解密、签名和验签操作。
 * RSA 是目前应用最广泛的非对称加密算法，适用于数据加密、数字签名和密钥交换。
 *
 * <h2>功能特性</h2>
 * <ul>
 *   <li>密钥对生成 — 支持 1024/2048/4096 位密钥长度，默认 2048 位</li>
 *   <li>数据加密 — 使用公钥加密数据</li>
 *   <li>数据解密 — 使用私钥解密数据</li>
 *   <li>数字签名 — 使用私钥对数据进行签名</li>
 *   <li>签名验证 — 使用公钥验证签名的有效性</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 1. 创建 RSA 加解密实例（指定密钥长度，默认 2048）
 * RsaCipher rsa = RsaCipher.create("bc");
 *
 * // 2. 生成密钥对
 * KeyPair keyPair = rsa.generateKeyPair();
 * byte[] publicKey = keyPair.getPublic().getEncoded();
 * byte[] privateKey = keyPair.getPrivate().getEncoded();
 *
 * // 3. 加密
 * byte[] ciphertext = rsa.encrypt(publicKey, plaintext);
 *
 * // 4. 解密
 * byte[] decrypted = rsa.decrypt(privateKey, ciphertext);
 *
 * // 5. 签名
 * byte[] signature = rsa.sign(privateKey, data);
 *
 * // 6. 验签
 * boolean isValid = rsa.verify(publicKey, data, signature);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 * @see Sm2Cipher
 * @see Cipher
 * @see com.chua.common.support.spi.ServiceProvider
 */
@NullMarked
public interface RsaCipher extends Cipher {

    /**
     * 创建指定提供者的 RSA 加解密实例
     *
     * @param provider 提供者名称，如 "bc"（BouncyCastle）
     * @return RsaCipher 实例
     * @throws com.chua.common.support.spi.ExtensionNotFoundException 当指定提供者不存在时抛出
     */
    static @Nullable RsaCipher create(String provider) {
        return ServiceProvider.of(RsaCipher.class).getNewExtension(provider);
    }

    /**
     * 生成 RSA 密钥对
     *
     * @param keySize 密钥长度（位数），常用值为 1024、2048、4096
     * @return 包含公钥和私钥的 KeyPair
     */
    KeyPair generateKeyPair(int keySize);

    /**
     * 生成默认长度（2048 位）的 RSA 密钥对
     *
     * @return 包含公钥和私钥的 KeyPair
     */
    default KeyPair generateKeyPair() {
        return generateKeyPair(2048);
    }

    /**
     * RSA 加密
     *
     * @param publicKey 公钥编码字节
     * @param data      待加密的明文数据
     * @return 加密后的密文数据
     */
    byte[] encrypt(byte[] publicKey, byte[] data);

    /**
     * RSA 解密
     *
     * @param privateKey 私钥编码字节
     * @param ciphertext 待解密的密文数据
     * @return 解密后的明文数据
     */
    byte[] decrypt(byte[] privateKey, byte[] ciphertext);

    /**
     * RSA 签名
     *
     * @param privateKey 私钥编码字节
     * @param data       待签名的数据
     * @return 签名值字节
     */
    byte[] sign(byte[] privateKey, byte[] data);

    /**
     * RSA 验签
     *
     * @param publicKey 公钥编码字节
     * @param data      原始数据
     * @param signature 待验证的签名值
     * @return true 表示签名验证通过
     */
    boolean verify(byte[] publicKey, byte[] data, byte[] signature);
}
