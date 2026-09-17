package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.ServiceProvider;

/**
* HPKE（Hybrid Public Key Encryption，RFC 9180）混合公钥加密 SPI 接口。
*
* <p>基于 SPI 机制加载实现，将 <b>密钥封装机制（KEM）</b> 与 <b>认证加密（AEAD）</b> 结合：
* 先通过椭圆曲线密钥协商封装/派生出一把对称密钥，再用该对称密钥对数据进行 AEAD 加密。
* 相比纯非对称加密（RSA/SM2）性能更高、更适合加密大数据；相比纯对称加密具备密钥协商能力、
* 无需预先共享对称密钥。
*
* <p>默认实现基于 X25519 + HKDF-SHA256 + AES-256-GCM（RFC 9180 base 模式，无 PSK）。
*
* <h2>原语</h2>
* 本接口提供 RFC 9180 base 模式的五个原语：{@link #generateKeyPair}、{@link #encap}、
* {@link #recoverKey}、{@link #seal}、{@link #open}。
*
* <h2>门面链式调用</h2>
* 需要把「封装 + 加密」「开包 + 解密」串成一条链时，请使用 {@link HpkeFlow} 门面：
* <pre>{@code
* byte[][] keys = HpkeFlow.of().keys();
*
* HpkeFlow.SealedMessage msg = HpkeFlow.of()
*         .receiverPk(keys[0]).aad(aad).seal(plain);      // 发送方
* byte[] out = HpkeFlow.of()
*         .secretKey(keys[1]).enc(msg.enc()).aad(aad).open(msg.ciphertext()); // 接收方
* }</pre>
*
* <p>说明：{@code ikm}（输入密钥材料，如共享口令）与 {@code aad}（附加认证数据）均可为 null，
* 发送方与接收方必须使用完全相同的 {@code ikm} 与 {@code aad} 才能成功解密。</p>
*
* @author CH
* @since 4.0.0.42
* @see HpkeFlow
* @see EciesCipher
* @see RsaCipher
* @see Cipher
* @see com.chua.common.support.spi.ServiceProvider
 */
public interface HpkeCipher extends Cipher {

    /**
    * 创建指定提供者的 HPKE 加解密实例。
    *
    * <p>通过 SPI 机制根据提供者名称加载对应的 HPKE 实现。
    * 当前内置基于 BouncyCastle 原语的实现，提供者名称为 "bc"。</p>
    *
    * @param provider 提供者名称，如 "bc"
    * @return HpkeCipher 实例
    * @throws com.chua.common.support.spi.ExtensionNotFoundException 当指定提供者不存在时抛出
    */
    static HpkeCipher create(String provider) {
        return ServiceProvider.of(HpkeCipher.class).getNewExtension(provider);
    }

    // ==================== 原语 ====================

    /**
    * 生成 KEM 密钥对。
    *
    * @return 长度 2 的数组：{@code [0]} 为公钥（32 字节），{@code [1]} 为私钥（32 字节）
    */
    byte[][] generateKeyPair();

    /**
    * KEM 封装（发送方）。
    *
    * <p>用接收方公钥与可选输入密钥材料（ikm）派生出一把对称密钥。
    * 产生的封装密钥 {@code enc} 需随密文一并发送给接收方。</p>
    *
    * @param receiverPublicKey 接收方公钥（32 字节）
    * @param ikm 可选输入密钥材料，可为 null
    * @return 长度 2 的数组：{@code [0]} 为封装密钥 enc（发送方随密文发给接收方），
    *         {@code [1]} 为对称密钥 ek（发送方保留用于 {@link #seal}）
    */
    byte[][] encap(byte[] receiverPublicKey, byte[] ikm);

    /**
    * KEM 开包（接收方）。
    *
    * <p>用接收方私钥、发送方的封装密钥 enc 与可选 ikm 恢复出与发送方一致的对称密钥。</p>
    *
    * @param receiverPrivateKey 接收方私钥（32 字节）
    * @param enc 发送方的封装密钥，见 {@link #encap} 返回值的 {@code [0]}
    * @param ikm 可选输入密钥材料，须与发送方 {@link #encap} 一致，可为 null
    * @return 对称密钥 ek，供 {@link #open} 使用
    */
    byte[] recoverKey(byte[] receiverPrivateKey, byte[] enc, byte[] ikm);

    /**
    * AEAD 加密（GCM，密文末尾自动拼接 16 字节认证标签）。
    *
    * @param ek 对称密钥，见 {@link #encap} 返回值的 {@code [1]}
    * @param aad 可选附加认证数据，可为 null
    * @param plaintext 待加密明文
    * @return 密文（含 16 字节认证标签）
    */
    byte[] seal(byte[] ek, byte[] aad, byte[] plaintext);

    /**
    * AEAD 解密（输入密文须含 16 字节认证标签）。
    *
    * @param ek 对称密钥，见 {@link #recoverKey}
    * @param aad 可选附加认证数据，须与加密时一致，可为 null
    * @param ciphertext 待解密密文（含 16 字节认证标签）
    * @return 解密后的明文
    */
    byte[] open(byte[] ek, byte[] aad, byte[] ciphertext);
}
