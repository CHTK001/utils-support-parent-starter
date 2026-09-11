package com.chua.common.support.lang.algorithm.cipher;


/**
 * 加解密算法顶级接口
 *
 * <p>所有加解密算法的顶层标记接口，定义了密码算法的统一抽象类型。
 * 具体算法实现通过 SPI 机制加载，支持非对称加密（如 SM2）和对称加密（如 SM4）两大类算法。
 *
 * <p>需要统一获取各算法实现并链式调用时，请使用 {@link CipherFactory}（统一工厂门面）。
 *
 * <h2>子接口</h2>
 * <ul>
 *   <li>{@link Sm2Cipher} — 基于 SM2 国密算法的非对称加解密接口，支持加密、解密、签名和验签</li>
 *   <li>{@link Sm4Cipher} — 基于 SM4 国密算法的对称加解密接口，支持 ECB/CBC 模式的加密与解密</li>
 *   <li>{@link HpkeCipher} — 基于 RFC 9180 的混合公钥加密接口（KEM + KDF + AEAD），支持链式门面调用</li>
 *   <li>{@link RsaCipher} / {@link EciesCipher} / {@link AesCipher} / {@link DesCipher} 等 — 其余算法</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 * @see CipherFactory
 */
public interface Cipher {
}
