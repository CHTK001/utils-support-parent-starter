package com.chua.common.support.lang.algorithm.cipher;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Objects;

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
 * <h2>两种使用方式</h2>
 * <ul>
 *   <li><b>原语</b>：直接调用 {@link #generateKeyPair()}/{@link #encap}/{@link #recoverKey}/{@link #seal}/{@link #open}</li>
 *   <li><b>门面链式调用</b>：通过 {@link #sender()}/{@link #receiver()} 构建器把「封装 + 加密」「开包 + 解密」
 *       串成一条链，一行完成，见下方示例</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * HpkeCipher hpke = HpkeCipher.create("bc");
 *
 * // 接收方：生成密钥对
 * byte[][] keys = hpke.generateKeyPair();
 * byte[] receiverPub = keys[0];
 * byte[] receiverPriv = keys[1];
 *
 * // ===== 门面链式调用（发送方）=====
 * // 封装 + AEAD 加密，一步完成；enc 需随密文一起发给接收方
 * byte[] aad = "order-1024".getBytes(StandardCharsets.UTF_8);
 * byte[] plain = "机密数据".getBytes(StandardCharsets.UTF_8);
 * HpkeCipher.SealedMessage msg = hpke.sender()
 *         .receiverPk(receiverPub)      // 接收方公钥
 *         .ikm(null)                    // 可选输入密钥材料
 *         .aad(aad)                     // 可选附加认证数据
 *         .seal(plain);
 *
 * // ===== 门面链式调用（接收方）=====
 * byte[] decrypted = hpke.receiver()
 *         .secretKey(receiverPriv)      // 接收方私钥
 *         .enc(msg.enc())               // 发送方封装密钥
 *         .ikm(null)
 *         .aad(aad)
 *         .open(msg.ciphertext());
 * }</pre>
 *
 * <p>说明：{@code ikm}（输入密钥材料，如共享口令）与 {@code aad}（附加认证数据）均可为空
 * （空数组），发送方与接收方必须使用完全相同的 {@code ikm} 与 {@code aad} 才能成功解密。</p>
 *
 * @author CH
 * @since 4.0.0.42
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
     * 当前内置基于 hpke-jdk 的实现，提供者名称为 "bc"。</p>
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

    // ==================== 门面链式调用 ====================

    /**
     * 进入发送方链式门面：封装 + 加密。
     *
     * @return 发送方构建器
     */
    default Sender sender() {
        return new Sender(this);
    }

    /**
     * 进入接收方链式门面：开包 + 解密。
     *
     * @return 接收方构建器
     */
    default Receiver receiver() {
        return new Receiver(this);
    }

    /**
     * 发送方链式构建器，把 {@link #encap} 与 {@link #seal} 串联为一条链。
     *
     * <pre>{@code
     * HpkeCipher.SealedMessage msg = hpke.sender()
     *         .receiverPk(receiverPub)
     *         .aad(aad)
     *         .seal(plaintext);
     * }</pre>
     */
    final class Sender {

        /** HPKE 原语实例 */
        private final HpkeCipher hpke;
        /** 接收方公钥 */
        private byte[] receiverPublicKey;
        /** 可选输入密钥材料 */
        private byte[] ikm;
        /** 可选附加认证数据 */
        private byte[] aad;

        /** 构造函数 */
        Sender(HpkeCipher hpke) {
            this.hpke = hpke;
        }

        /**
         * 设置接收方公钥。
         *
         * @param receiverPublicKey 接收方公钥（32 字节）
         * @return 当前构建器（链式调用）
         */
        public Sender receiverPk(byte[] receiverPublicKey) {
            this.receiverPublicKey = receiverPublicKey;
            return this;
        }

        /**
         * 设置可选输入密钥材料。
         *
         * @param ikm 输入密钥材料，可为 null
         * @return 当前构建器（链式调用）
         */
        public Sender ikm(byte[] ikm) {
            this.ikm = ikm;
            return this;
        }

        /**
         * 设置可选附加认证数据。
         *
         * @param aad 附加认证数据，可为 null
         * @return 当前构建器（链式调用）
         */
        public Sender aad(byte[] aad) {
            this.aad = aad;
            return this;
        }

        /**
         * 执行封装 + 加密，一步完成。
         *
         * @param plaintext 待加密明文
         * @return 含封装密钥与密文的消息体
         * @throws NullPointerException 未通过 {@link #receiverPk} 设置接收方公钥时
         */
        public SealedMessage seal(byte[] plaintext) {
            Objects.requireNonNull(receiverPublicKey, "receiverPk 未设置");
            byte[][] result = hpke.encap(receiverPublicKey, ikm);
            byte[] ek = hpke.seal(result[1], aad, plaintext);
            return new SealedMessage(result[0], ek);
        }
    }

    /**
     * 接收方链式构建器，把 {@link #recoverKey} 与 {@link #open} 串联为一条链。
     *
     * <pre>{@code
     * byte[] plain = hpke.receiver()
     *         .secretKey(receiverPriv)
     *         .enc(msg.enc())
     *         .aad(aad)
     *         .open(msg.ciphertext());
     * }</pre>
     */
    final class Receiver {

        /** HPKE 原语实例 */
        private final HpkeCipher hpke;
        /** 接收方私钥 */
        private byte[] secretKey;
        /** 发送方的封装密钥 */
        private byte[] enc;
        /** 可选输入密钥材料 */
        private byte[] ikm;
        /** 可选附加认证数据 */
        private byte[] aad;

        /** 构造函数 */
        Receiver(HpkeCipher hpke) {
            this.hpke = hpke;
        }

        /**
         * 设置接收方私钥。
         *
         * @param secretKey 接收方私钥（32 字节）
         * @return 当前构建器（链式调用）
         */
        public Receiver secretKey(byte[] secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        /**
         * 设置发送方的封装密钥。
         *
         * @param enc 发送方封装密钥，见 {@link SealedMessage#enc()}
         * @return 当前构建器（链式调用）
         */
        public Receiver enc(byte[] enc) {
            this.enc = enc;
            return this;
        }

        /**
         * 设置可选输入密钥材料（须与发送方一致）。
         *
         * @param ikm 输入密钥材料，可为 null
         * @return 当前构建器（链式调用）
         */
        public Receiver ikm(byte[] ikm) {
            this.ikm = ikm;
            return this;
        }

        /**
         * 设置可选附加认证数据（须与发送方一致）。
         *
         * @param aad 附加认证数据，可为 null
         * @return 当前构建器（链式调用）
         */
        public Receiver aad(byte[] aad) {
            this.aad = aad;
            return this;
        }

        /**
         * 执行开包 + 解密，一步完成。
         *
         * @param ciphertext 待解密密文（含 16 字节认证标签）
         * @return 解密后的明文
         * @throws NullPointerException 未通过 {@link #secretKey} 或 {@link #enc} 设置时
         */
        public byte[] open(byte[] ciphertext) {
            Objects.requireNonNull(secretKey, "secretKey 未设置");
            Objects.requireNonNull(enc, "enc 未设置");
            byte[] ek = hpke.recoverKey(secretKey, enc, ikm);
            return hpke.open(ek, aad, ciphertext);
        }
    }

    /**
     * 发送方产出物：封装密钥（需随密文传输）与 AEAD 密文。
     */
    final class SealedMessage {

        /** 封装密钥，须随密文发给接收方 */
        private final byte[] enc;
        /** AEAD 密文（含 16 字节认证标签） */
        private final byte[] ciphertext;

        /** 构造函数 */
        SealedMessage(byte[] enc, byte[] ciphertext) {
            this.enc = enc;
            this.ciphertext = ciphertext;
        }

        /**
         * 获取封装密钥（发送给接收方）。
         *
         * @return 封装密钥
         */
        public byte[] enc() {
            return enc;
        }

        /**
         * 获取 AEAD 密文。
         *
         * @return 密文（含 16 字节认证标签）
         */
        public byte[] ciphertext() {
            return ciphertext;
        }
    }
}
