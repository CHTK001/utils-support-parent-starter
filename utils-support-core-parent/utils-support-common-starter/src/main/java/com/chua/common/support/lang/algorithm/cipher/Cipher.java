package com.chua.common.support.lang.algorithm.cipher;

/**
 * 加解密算法顶级接口
 *
 * <p>所有加解密算法的顶层标记接口，定义了密码算法的统一抽象类型。
 * 具体算法实现通过 SPI 机制加载，支持非对称加密（如 SM2）和对称加密（如 SM4）两大类算法。
 *
 * <h2>子接口</h2>
 * <ul>
 *   <li>{@link Sm2Cipher} — 基于 SM2 国密算法的非对称加解密接口，支持加密、解密、签名和验签</li>
 *   <li>{@link Sm4Cipher} — 基于 SM4 国密算法的对称加解密接口，支持 ECB/CBC 模式的加密与解密</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // SM2 非对称加解密
 * Sm2Cipher sm2 = Sm2Cipher.create("bc");
 * KeyPair keyPair = sm2.generateKeyPair();
 * byte[] encrypted = sm2.encrypt(publicKey, data);
 * byte[] decrypted = sm2.decrypt(privateKey, encrypted);
 *
 * // SM4 对称加解密
 * Sm4Cipher sm4 = Sm4Cipher.create("bc");
 * byte[] encrypted = sm4.encrypt(key, data);
 * String encryptedStr = sm4.encryptToString(key, "明文数据");
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
public interface Cipher {
}
