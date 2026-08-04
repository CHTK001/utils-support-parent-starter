package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.ServiceProvider;

import java.security.KeyPair;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * SM2 非对称加解密接口
 *
 * <p>基于 SPI 机制加载实现，支持国密 SM2 算法的密钥生成、加密、解密、签名和验签操作。
 * SM2 是国家密码管理局发布的椭圆曲线公钥密码算法（GM/T 0003-2012），
 * 适用于商用密码应用中的数字签名、密钥协商和数据加密。
 *
 * <h2>功能特性</h2>
 * <ul>
 *   <li>密钥对生成 — 基于 sm2p256v1 椭圆曲线参数生成 SM2 密钥对</li>
 *   <li>数据加密 — 使用公钥加密数据，采用 C1C3C2 模式的 SM2Engine</li>
 *   <li>数据解密 — 使用私钥解密数据</li>
 *   <li>数字签名 — 使用私钥对数据进行签名</li>
 *   <li>签名验证 — 使用公钥验证签名的有效性</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 1. 创建 SM2 加解密实例
 * Sm2Cipher sm2 = Sm2Cipher.create("bc");
 *
 * // 2. 生成密钥对
 * KeyPair keyPair = sm2.generateKeyPair();
 * byte[] publicKey = keyPair.getPublic().getEncoded();
 * byte[] privateKey = keyPair.getPrivate().getEncoded();
 *
 * // 3. 加密数据
 * byte[] plaintext = "待加密的敏感数据".getBytes(StandardCharsets.UTF_8);
 * byte[] ciphertext = sm2.encrypt(publicKey, plaintext);
 *
 * // 4. 解密数据
 * byte[] decrypted = sm2.decrypt(privateKey, ciphertext);
 *
 * // 5. 签名
 * byte[] signature = sm2.sign(privateKey, data);
 *
 * // 6. 验签
 * boolean isValid = sm2.verify(publicKey, data, signature);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 * @see Sm4Cipher
 * @see Cipher
 * @see com.chua.common.support.spi.ServiceProvider
 */
@NullMarked
public interface Sm2Cipher extends Cipher {

    /**
     * 创建指定提供者的 SM2 加解密实例
     *
     * <p>通过 SPI 机制根据提供者名称加载对应的 SM2 实现。
     * 当前内置支持 BouncyCastle 实现，提供者名称为 "bc"。
     *
     * @param provider 提供者名称，如 "bc"（BouncyCastle）
     * @return Sm2Cipher 实例
     * @throws com.chua.common.support.spi.ExtensionNotFoundException 当指定提供者不存在时抛出
     */
    static @Nullable Sm2Cipher create(String provider) {
        return ServiceProvider.of(Sm2Cipher.class).getNewExtension(provider);
    }

    /**
     * 生成 SM2 密钥对
     *
     * <p>基于 sm2p256v1 椭圆曲线参数生成包含公钥和私钥的密钥对。
     * 生成的密钥对可分别用于加密/验签（公钥）和解密/签名（私钥）。
     *
     * @return 包含公钥和私钥的 KeyPair
     */
    KeyPair generateKeyPair();

    /**
     * SM2 加密
     *
     * <p>使用公钥对明文数据进行加密，采用 C1C3C2 模式的 SM2 加密引擎。
     * 加密后的密文数据可通过对应的 {@link #decrypt(byte[], byte[])} 方法解密。
     *
     * @param publicKey 公钥编码字节，由 {@link #generateKeyPair()} 生成的公钥编码得到
     * @param data      待加密的明文数据
     * @return 加密后的密文数据
     */
    byte[] encrypt(byte[] publicKey, byte[] data);

    /**
     * SM2 解密
     *
     * <p>使用私钥对密文数据进行解密，还原为原始明文数据。
     * 解密所使用的私钥必须与加密时使用的公钥属于同一密钥对。
     *
     * @param privateKey 私钥编码字节，由 {@link #generateKeyPair()} 生成的私钥编码得到
     * @param ciphertext 待解密的密文数据
     * @return 解密后的明文数据
     */
    byte[] decrypt(byte[] privateKey, byte[] ciphertext);

    /**
     * SM2 签名
     *
     * <p>使用私钥对指定数据进行数字签名，生成签名值。
     * 签名数据可通过对应的 {@link #verify(byte[], byte[], byte[])} 方法验证。
     *
     * @param privateKey 私钥编码字节
     * @param data       待签名的数据
     * @return 签名值字节
     */
    byte[] sign(byte[] privateKey, byte[] data);

    /**
     * SM2 验签
     *
     * <p>使用公钥验证指定数据的签名是否有效。
     * 验签所使用的公钥必须与签名时使用的私钥属于同一密钥对。
     *
     * @param publicKey 公钥编码字节
     * @param data      原始数据
     * @param signature 待验证的签名值
     * @return true 表示签名验证通过，false 表示验证失败
     */
    boolean verify(byte[] publicKey, byte[] data, byte[] signature);
}
