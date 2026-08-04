package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.ServiceProvider;
import org.jspecify.annotations.NullUnmarked;

/**
 * SM4 对称加解密接口
 *
 * <p>基于 SPI 机制加载实现，支持国密 SM4 算法的 ECB/CBC 模式加密与解密。
 * SM4 是国家密码管理局发布的无线局域网标准的分组密码算法（GM/T 0002-2012），
 * 密钥长度为 128 位（16 字节），分组长度为 128 位。
 *
 * <h2>功能特性</h2>
 * <ul>
 *   <li>数据加密 — 使用 128 位密钥对数据进行加密，支持 PKCS7Padding 填充</li>
 *   <li>数据解密 — 使用 128 位密钥对密文数据进行解密</li>
 *   <li>字符串模式 — 提供便捷的 Base64 编码/解码的字符串加解密方法</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 1. 创建 SM4 加解密实例
 * Sm4Cipher sm4 = Sm4Cipher.create("bc");
 *
 * // 2. 准备 16 字节密钥
 * byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
 *
 * // 3. 加密数据（字节模式）
 * byte[] plaintext = "待加密的敏感数据".getBytes(StandardCharsets.UTF_8);
 * byte[] ciphertext = sm4.encrypt(key, plaintext);
 *
 * // 4. 解密数据（字节模式）
 * byte[] decrypted = sm4.decrypt(key, ciphertext);
 *
 * // 5. 加密数据（字符串模式）
 * String encryptedStr = sm4.encryptToString(key, "待加密的敏感数据");
 *
 * // 6. 解密数据（字符串模式）
 * String decryptedStr = sm4.decryptToString(key, encryptedStr);
 * }</pre>
 *
 * <h2>密钥要求</h2>
 * <p>SM4 算法要求密钥长度必须为 16 字节（128 位），
 * 传入不合法长度的密钥将抛出 {@link IllegalArgumentException}。
 *
 * @author CH
 * @since 2026/07/16
 * @see Sm2Cipher
 * @see Cipher
 * @see com.chua.common.support.spi.ServiceProvider
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public interface Sm4Cipher extends Cipher {

    /**
     * 创建指定提供者的 SM4 加解密实例
     *
     * <p>通过 SPI 机制根据提供者名称加载对应的 SM4 实现。
     * 当前内置支持 BouncyCastle 实现，提供者名称为 "bc"。
     *
     * @param provider 提供者名称，如 "bc"（BouncyCastle）
     * @return Sm4Cipher 实例
     * @throws com.chua.common.support.spi.ExtensionNotFoundException 当指定提供者不存在时抛出
     */
    static Sm4Cipher create(String provider) {
        return ServiceProvider.of(Sm4Cipher.class).getNewExtension(provider);
    }

    /**
     * SM4 加密
     *
     * <p>使用指定的 128 位密钥对明文数据进行加密，
     * 采用 ECB/CBC 模式配合 PKCS7Padding 填充方式。
     *
     * @param key  加密密钥，长度必须为 16 字节（128 位）
     * @param data 待加密的明文数据
     * @return 加密后的密文数据
     */
    byte[] encrypt(byte[] key, byte[] data);

    /**
     * SM4 解密
     *
     * <p>使用指定的 128 位密钥对密文数据进行解密，
     * 还原为原始明文数据。
     *
     * @param key        解密密钥，长度必须为 16 字节（128 位），必须与加密时使用的密钥一致
     * @param ciphertext 待解密的密文数据
     * @return 解密后的明文数据
     */
    byte[] decrypt(byte[] key, byte[] ciphertext);

    /**
     * SM4 加密（字符串模式）
     *
     * <p>对明文字符串进行加密，并将密文结果以 Base64 编码的字符串形式返回。
     * 内部调用 {@link #encrypt(byte[], byte[])} 方法，使用 UTF-8 编码将字符串转为字节数组。
     *
     * @param key  加密密钥，长度必须为 16 字节（128 位）
     * @param data 待加密的明文字符串
     * @return Base64 编码的密文字符串
     */
    default String encryptToString(byte[] key, String data) {
        return java.util.Base64.getEncoder().encodeToString(
                encrypt(key, data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * SM4 解密（字符串模式）
     *
     * <p>对 Base64 编码的密文字符串进行解密，返回原始明文字符串。
     * 内部调用 {@link #decrypt(byte[], byte[])} 方法，使用 UTF-8 编码将字节数组转为字符串。
     *
     * @param key          解密密钥，长度必须为 16 字节（128 位），必须与加密时使用的密钥一致
     * @param ciphertext64 Base64 编码的密文字符串
     * @return 解密后的明文字符串
     */
    default String decryptToString(byte[] key, String ciphertext64) {
        return new String(
                decrypt(key, java.util.Base64.getDecoder().decode(ciphertext64)),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
