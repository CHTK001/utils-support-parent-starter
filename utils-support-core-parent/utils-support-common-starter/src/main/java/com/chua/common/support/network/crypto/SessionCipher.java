package com.chua.common.support.network.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

/**
 * 会话级认证加密：根密钥 + 每次连接随机盐 → 双向独立密钥 → AES-256-GCM。
 *
 * <p>解决的是「一条长连接用一个固定密钥」的老问题：根密钥只在两端的内存里，
 * 线路上只交换 16 字节随机盐；两端的发送与接收各用一把由 HKDF 派生的子密钥，
 * 一把密钥泄露不会连带解开另一方向，也不会牵连其它连接。</p>
 *
 * <h2>帧格式</h2>
 * <pre>
 * [4B 长度][8B 序号][密文 + 16B 认证标签]
 * </pre>
 * <p>长度字段为「序号 + 密文」的总长。nonce 不传输，由盐的前 4 字节与 8 字节序号拼成，
 * 因此同一条连接内永不重复；附加认证数据（AAD）绑定方向与序号，改包、重放、
 * 乱序都会被拒绝，而不是解出垃圾。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * byte[] rootKey = ...;                       // 两端共享，永不外传
 * byte[] salt = SessionCipher.newSalt();      // 由发起方生成并随握手发送
 * SessionCipher client = SessionCipher.initiator(rootKey, salt);
 * SessionCipher server = SessionCipher.responder(rootKey, salt);
 *
 * byte[] frame = client.seal("hello".getBytes(StandardCharsets.UTF_8));
 * byte[] plain = server.open(frame);
 * }</pre>
 *
 * <p>本类不做握手，也不负责盐的传输与身份认证；它只保证「拿到同一根密钥与同一盐，
 * 才能解开这段流量」。盐建议随每次连接随机生成，连接之间不要复用。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class SessionCipher {

    /**
     * 盐长度（字节）
     */
    public static final int SALT_LENGTH = 16;

    /**
     * 子密钥长度（字节）
     */
    public static final int KEY_LENGTH = 32;

    /**
     * GCM nonce 长度（字节）
     */
    public static final int NONCE_LENGTH = 12;

    /**
     * GCM 认证标签长度（字节）
     */
    public static final int TAG_LENGTH = 16;

    /**
     * 序号长度（字节）
     */
    public static final int SEQ_LENGTH = 8;

    /**
     * 长度字段长度（字节）
     */
    public static final int LENGTH_FIELD = 4;

    /**
     * 单帧明文上限（字节）
     */
    public static final int MAX_PLAIN_LENGTH = 64 * 1024;

    /**
     * 单帧总长上限（字节）
     */
    public static final int MAX_FRAME_LENGTH = LENGTH_FIELD + SEQ_LENGTH + MAX_PLAIN_LENGTH + TAG_LENGTH;

    /**
     * 根密钥最小长度（字节）
     */
    private static final int MIN_ROOT_KEY_LENGTH = 16;

    /**
     * 加密变换名
     */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * AES 算法名
     */
    private static final String AES_ALGORITHM = "AES";

    /**
     * 发起方到响应方的派生标签
     */
    private static final String INFO_INITIATOR = "chua/session/v1/initiator-to-responder";

    /**
     * 响应方到发起方的派生标签
     */
    private static final String INFO_RESPONDER = "chua/session/v1/responder-to-initiator";

    /**
     * 发起方方向标识
     */
    private static final byte DIRECTION_INITIATOR = 1;

    /**
     * 响应方方向标识
     */
    private static final byte DIRECTION_RESPONDER = 2;

    /**
     * 发送方向标识
     */
    private final byte sendDirection;

    /**
     * 接收方向标识
     */
    private final byte receiveDirection;

    /**
     * 发送密钥
     */
    private final SecretKeySpec sendKey;

    /**
     * 接收密钥
     */
    private final SecretKeySpec receiveKey;

    /**
     * nonce 前缀（盐的前 4 字节）
     */
    private final byte[] noncePrefix;

    /**
     * 发送序号，从 0 开始
     */
    private long sendSequence;

    /**
     * 最近一次成功接收的序号，-1 表示尚未收到
     */
    private long receiveSequence = -1L;

    /**
     * 构造会话（密钥方向由 roles 决定）。
     *
     * @param rootKey   根密钥，长度不小于 16 字节
     * @param salt      每次连接随机的 16 字节盐
     * @param initiator 当前端是否为发起方
     */
    private SessionCipher(byte[] rootKey, byte[] salt, boolean initiator) {
        if (rootKey == null || rootKey.length < MIN_ROOT_KEY_LENGTH) {
            throw new IllegalArgumentException("根密钥长度不能小于 " + MIN_ROOT_KEY_LENGTH + " 字节");
        }
        if (salt == null || salt.length != SALT_LENGTH) {
            throw new IllegalArgumentException("盐长度必须为 " + SALT_LENGTH + " 字节");
        }
        this.sendDirection = initiator ? DIRECTION_INITIATOR : DIRECTION_RESPONDER;
        this.receiveDirection = initiator ? DIRECTION_RESPONDER : DIRECTION_INITIATOR;
        byte[] sendSecret = Hkdf.derive(rootKey, salt, initiator ? INFO_INITIATOR : INFO_RESPONDER, KEY_LENGTH);
        byte[] receiveSecret = Hkdf.derive(rootKey, salt, initiator ? INFO_RESPONDER : INFO_INITIATOR, KEY_LENGTH);
        this.sendKey = new SecretKeySpec(sendSecret, AES_ALGORITHM);
        this.receiveKey = new SecretKeySpec(receiveSecret, AES_ALGORITHM);
        this.noncePrefix = new byte[LENGTH_FIELD];
        System.arraycopy(salt, 0, noncePrefix, 0, LENGTH_FIELD);
    }

    /**
     * 创建发起方会话：发送用「发起方 → 响应方」密钥，接收用反向密钥。
     *
     * @param rootKey 根密钥
     * @param salt    与对端一致的 16 字节盐
     * @return 会话实例
     */
    public static SessionCipher initiator(byte[] rootKey, byte[] salt) {
        return new SessionCipher(rootKey, salt, true);
    }

    /**
     * 创建响应方会话：发送用「响应方 → 发起方」密钥，接收用反向密钥。
     *
     * @param rootKey 根密钥
     * @param salt    与对端一致的 16 字节盐
     * @return 会话实例
     */
    public static SessionCipher responder(byte[] rootKey, byte[] salt) {
        return new SessionCipher(rootKey, salt, false);
    }

    /**
     * 生成每次连接使用的随机盐。
     *
     * @return 16 字节随机盐
     */
    public static byte[] newSalt() {
        return Hkdf.randomBytes(SALT_LENGTH);
    }

    /**
     * 加密一帧，序号自增。
     *
     * @param plain 明文，长度不超过 {@link #MAX_PLAIN_LENGTH}
     * @return 完整帧（含长度与序号）
     */
    public synchronized byte[] seal(byte[] plain) {
        if (plain == null) {
            throw new IllegalArgumentException("明文不能为空");
        }
        if (plain.length > MAX_PLAIN_LENGTH) {
            throw new IllegalArgumentException("单帧明文不能超过 " + MAX_PLAIN_LENGTH + " 字节");
        }
        if (sendSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("会话序号已耗尽，请重新握手");
        }
        long sequence = sendSequence++;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(TAG_LENGTH * Byte.SIZE, nonce(sequence)));
            cipher.updateAAD(aad(sendDirection, sequence));
            byte[] cipherText = cipher.doFinal(plain);
            ByteBuffer buffer = ByteBuffer.allocate(LENGTH_FIELD + SEQ_LENGTH + cipherText.length);
            buffer.putInt(SEQ_LENGTH + cipherText.length);
            buffer.putLong(sequence);
            buffer.put(cipherText);
            return buffer.array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("会话帧加密失败", e);
        }
    }

    /**
     * 解密一帧，只接受连续序号；重放、乱序与篡改都会抛异常。
     *
     * @param frame 完整帧
     * @return 明文
     */
    public synchronized byte[] open(byte[] frame) {
        if (frame == null || frame.length < LENGTH_FIELD + SEQ_LENGTH + TAG_LENGTH) {
            throw new IllegalArgumentException("会话帧长度不足");
        }
        if (frame.length > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("会话帧超过上限 " + MAX_FRAME_LENGTH + " 字节");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        int bodyLength = buffer.getInt();
        if (bodyLength != frame.length - LENGTH_FIELD) {
            throw new IllegalArgumentException("会话帧长度字段与实际长度不一致");
        }
        long sequence = buffer.getLong();
        if (sequence != receiveSequence + 1) {
            throw new SecurityException("会话帧序号不连续，疑似重放或丢帧: " + sequence);
        }
        byte[] cipherText = new byte[bodyLength - SEQ_LENGTH];
        buffer.get(cipherText);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, receiveKey, new GCMParameterSpec(TAG_LENGTH * Byte.SIZE, nonce(sequence)));
            cipher.updateAAD(aad(receiveDirection, sequence));
            byte[] plain = cipher.doFinal(cipherText);
            receiveSequence = sequence;
            return plain;
        } catch (GeneralSecurityException e) {
            throw new SecurityException("会话帧解密失败（密钥不一致或数据被篡改）", e);
        }
    }

    /**
     * 由 nonce 前缀与序号拼出 12 字节 nonce。
     *
     * @param sequence 序号
     * @return nonce
     */
    private byte[] nonce(long sequence) {
        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(noncePrefix, 0, nonce, 0, LENGTH_FIELD);
        ByteBuffer.wrap(nonce, LENGTH_FIELD, SEQ_LENGTH).putLong(sequence);
        return nonce;
    }

    /**
     * 构造附加认证数据：方向 + 序号。
     *
     * @param direction 方向标识
     * @param sequence  序号
     * @return AAD
     */
    private byte[] aad(byte direction, long sequence) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + SEQ_LENGTH);
        buffer.put(direction);
        buffer.putLong(sequence);
        return buffer.array();
    }
}
