package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.ServiceProvider;

import java.security.KeyPair;

/**
 * ECIES 椭圆曲线集成加密方案 SPI 接口
 *
 * <p>基于 SPI 机制加载实现，支持 ECIES 混合加密方案（密钥封装 + 对称加密）。
 * 使用椭圆曲线生成共享密钥，通过 KDF 派生对称密钥后加密数据，
 * 加密输出包含临时公钥、IV 和密文。
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建 ECIES 加解密实例
 * EciesCipher cipher = EciesCipher.create("bc");
 *
 * // 生成密钥对
 * KeyPair keyPair = cipher.generateKeyPair();
 * byte[] publicKey = keyPair.getPublic().getEncoded();
 * byte[] privateKey = keyPair.getPrivate().getEncoded();
 *
 * // 加密
 * byte[] ciphertext = cipher.encrypt(publicKey, plaintext);
 *
 * // 解密
 * byte[] decrypted = cipher.decrypt(privateKey, ciphertext);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
public interface EciesCipher extends Cipher {

    /**
     * 创建指定提供者的 ECIES 加解密实例
     *
     * @param provider 提供者名称，如 "bc"（BouncyCastle）
     * @return EciesCipher 实例
     * @throws com.chua.common.support.spi.ExtensionNotFoundException 当指定提供者不存在时抛出
     */
    static EciesCipher create(String provider) {
        return ServiceProvider.of(EciesCipher.class).getNewExtension(provider);
    }

    /**
     * 生成椭圆曲线密钥对
     *
     * @return 包含公钥和私钥的 KeyPair
     */
    KeyPair generateKeyPair();

    /**
     * ECIES 加密
     *
     * @param publicKey 公钥编码字节
     * @param data      待加密的明文数据
     * @return 加密后的密文数据
     */
    byte[] encrypt(byte[] publicKey, byte[] data);

    /**
     * ECIES 解密
     *
     * @param privateKey 私钥编码字节
     * @param ciphertext 待解密的密文数据
     * @return 解密后的明文数据
     */
    byte[] decrypt(byte[] privateKey, byte[] ciphertext);
}