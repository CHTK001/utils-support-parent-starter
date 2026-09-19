package com.chua.common.support.lang.algorithm.cipher;

import java.util.Objects;

/**
 * HPKE（RFC 9180）混合公钥加密门面，支持链式调用。
 *
 * <p>遵循项目 {@code XxxFlow} 家风：{@code HpkeFlow.of()} 造实例，配置方法返回 {@code this}
 * 链式，终结方法出结果。把「KEM 封装 + AEAD 加密」「KEM 开包 + AEAD 解密」串成一条链。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 生成密钥对（[0]=公钥, [1]=私钥）
 * byte[][] keys = HpkeFlow.of().keys();
 *
 * // 发送方：封装 + 加密
 * HpkeFlow.SealedMessage msg = HpkeFlow.of()
 *         .receiverPk(keys[0])
 *         .ikm(null)
 *         .aad("order-1024".getBytes())
 *         .seal("机密数据".getBytes());
 *
 * // 接收方：开包 + 解密（enc 需来自发送方 msg.enc()）
 * byte[] plain = HpkeFlow.of()
 *         .secretKey(keys[1])
 *         .enc(msg.enc())
 *         .ikm(null)
 *         .aad("order-1024".getBytes())
 *         .open(msg.ciphertext());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see HpkeCipher
 * @see CipherFlow
 */
public final class HpkeFlow {

    /** 底层 HPKE 原语实例 */
    private final HpkeCipher cipher;
    /** 接收方公钥（发送方路径） */
    private byte[] receiverPublicKey;
    /** 接收方私钥（接收方路径） */
    private byte[] secretKey;
    /** 发送方封装密钥（接收方路径） */
    private byte[] enc;
    /** 可选输入密钥材料（须收发一致） */
    private byte[] ikm;
    /** 可选附加认证数据（须收发一致） */
    private byte[] aad;

    /**
     * 构造函数，传入底层原语
     * @param cipher 方法入参 cipher
     */
    private HpkeFlow(HpkeCipher cipher) {
        this.cipher = cipher;
    }

    /**
     * 创建默认提供者（"bc"）的 HPKE 门面。
     *
     * @return 门面实例
     */
    public static HpkeFlow of() {
        return of("bc");
    }

    /**
     * 创建指定提供者的 HPKE 门面。
     *
     * @param provider 提供者名称，如 "bc"
     * @return 门面实例
     */
    public static HpkeFlow of(String provider) {
        return new HpkeFlow(HpkeCipher.create(provider));
    }

    /**
     * 创建基于已有原语实例的 HPKE 门面。
     *
     * @param cipher 底层原语，不能为 null
     * @return 门面实例
     */
    public static HpkeFlow of(HpkeCipher cipher) {
        Objects.requireNonNull(cipher, "cipher 不能为 null");
        return new HpkeFlow(cipher);
    }

    // ==================== 配置（链式，返回 this） ====================

    /**
     * 设置接收方公钥（发送方路径）。
     *
     * @param receiverPublicKey 接收方公钥（32 字节）
     * @return 当前门面
     */
    public HpkeFlow receiverPk(byte[] receiverPublicKey) {
        this.receiverPublicKey = receiverPublicKey;
        return this;
    }

    /**
     * 设置接收方私钥（接收方路径）。
     *
     * @param secretKey 接收方私钥（32 字节）
     * @return 当前门面
     */
    public HpkeFlow secretKey(byte[] secretKey) {
        this.secretKey = secretKey;
        return this;
    }

    /**
     * 设置发送方封装密钥（接收方路径）。
     *
     * @param enc 发送方封装密钥，见 {@link SealedMessage#enc()}
     * @return 当前门面
     */
    public HpkeFlow enc(byte[] enc) {
        this.enc = enc;
        return this;
    }

    /**
     * 设置可选输入密钥材料（须与对端一致）。
     *
     * @param ikm 输入密钥材料，可为 null
     * @return 当前门面
     */
    public HpkeFlow ikm(byte[] ikm) {
        this.ikm = ikm;
        return this;
    }

    /**
     * 设置可选附加认证数据（须与对端一致）。
     *
     * @param aad 附加认证数据，可为 null
     * @return 当前门面
     */
    public HpkeFlow aad(byte[] aad) {
        this.aad = aad;
        return this;
    }

    // ==================== 终结操作 ====================

    /**
     * 生成 KEM 密钥对。
     *
     * @return 长度 2 数组：{@code [0]}=公钥、{@code [1]}=私钥（各 32 字节）
     */
    public byte[][] keys() {
        return cipher.generateKeyPair();
    }

    /**
     * 发送方：KEM 封装 + AEAD 加密，一步完成。
     *
     * @param plaintext 待加密明文
     * @return 含封装密钥与密文的消息体
     * @throws NullPointerException 未通过 {@link #receiverPk} 设置接收方公钥时
     */
    public SealedMessage seal(byte[] plaintext) {
        Objects.requireNonNull(receiverPublicKey, "receiverPk 未设置");
        byte[][] result = cipher.encap(receiverPublicKey, ikm);
        byte[] ciphertext = cipher.seal(result[1], aad, plaintext);
        return new SealedMessage(result[0], ciphertext);
    }

    /**
     * 接收方：KEM 开包 + AEAD 解密，一步完成。
     *
     * @param ciphertext 待解密密文（含 16 字节认证标签）
     * @return 解密后的明文
     * @throws NullPointerException 未通过 {@link #secretKey} 或 {@link #enc} 设置时
     */
    public byte[] open(byte[] ciphertext) {
        Objects.requireNonNull(secretKey, "secretKey 未设置");
        Objects.requireNonNull(enc, "enc 未设置");
        byte[] ek = cipher.recoverKey(secretKey, enc, ikm);
        return cipher.open(ek, aad, ciphertext);
    }

    /**
     * 获取底层原语实例。
     *
     * @return 底层 {@link HpkeCipher}
     */
    public HpkeCipher cipher() {
        return cipher;
    }

    /**
     * HPKE 发送方产出物：封装密钥（需随密文传输）与 AEAD 密文。
     */
    public static final class SealedMessage {

        /** 封装密钥，须随密文发给接收方 */
        private final byte[] enc;
        /** AEAD 密文（含 16 字节认证标签） */
        private final byte[] ciphertext;

        /** 构造函数 */
        public SealedMessage(byte[] enc, byte[] ciphertext) {
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
