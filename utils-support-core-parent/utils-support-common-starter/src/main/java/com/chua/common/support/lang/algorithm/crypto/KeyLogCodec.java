// [P3C A20 豁免] A20/A30/A31: 含算法名/模式名等协议常量，属非业务魔法值场景。
package com.chua.common.support.lang.algorithm.crypto;

import com.chua.common.support.network.crypto.Hkdf;
import com.chua.common.support.spi.annotations.Spi;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次性会话密钥加解密（临时 ECDH 密钥交换 + AEAD）。
 *
 * <p>服务端持有一对长期 X25519 密钥：公钥公开给客户端，<b>私钥只在进程内存</b>。
 * 客户端每发一条消息都现场生成临时 X25519 密钥对，用服务端公钥做 ECDH，
 * 经 HKDF-SHA256 派生一把一次性 AES-256 密钥，再用 AES-256-GCM 加密；
 * 临时公钥 {@code enc} 随密文一起发出。服务端用自己的长期私钥与 {@code enc}
 * 重算出同一把密钥即可解密——<b>不查表、不存会话密钥、不落盘</b>。</p>
 *
 * <h2>密文格式</h2>
 * <pre>
 * k2: + hex( [8B 时间戳][8B 消息ID][1B enc 长度][enc = 临时 X.509 公钥]
 *            [12B nonce][GCM 密文 + 16B 标签] )
 * </pre>
 * <p>时间戳与消息ID一起进入 AAD，改任何一个都会让认证失败；服务端默认只接受
 * 5 分钟窗口内的消息，并拒绝重复的消息ID。</p>
 *
 * <h2>密钥绑定格式</h2>
 * <pre>{@code
 * String[] pair = KeyLogCodec.generateKeyPair();   // [0]=公钥hex, [1]=私钥hex
 *
 * // 客户端：公钥可带指纹钉死（推荐），公钥被替换时直接拒绝
 * String pin = KeyLogCodec.fingerprint(pair[0]);
 * Codec client = Codec.build("keylog", "client:" + pair[0] + "#" + pin);
 *
 * // 服务端：只需要私钥，只能解密
 * Codec server = Codec.build("keylog", "server:" + pair[1]);
 *
 * // 单进程双向（测试/回包场景）
 * Codec both = Codec.build("keylog", "pair:" + pair[0] + ":" + pair[1]);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"keylog", "hpke"})
public class KeyLogCodec implements Codec {

    /**
     * 密文前缀，与 {@code aes-gcm} 的 {@code g1:} 区分
     */
    private static final String PREFIX = "k2:";

    /**
     * 椭圆曲线算法名
     */
    private static final String X25519 = "X25519";

    /**
     * AEAD 变换名
     */
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * AES 算法名
     */
    private static final String AES_ALGORITHM = "AES";

    /**
     * GCM nonce 长度（字节）
     */
    private static final int NONCE_LENGTH = 12;

    /**
     * GCM 认证标签长度（比特）
     */
    private static final int TAG_BITS = 128;

    /**
     * GCM 认证标签长度（字节）
     */
    private static final int TAG_LENGTH = TAG_BITS / Byte.SIZE;

    /**
     * 派生对称密钥长度（字节）
     */
    private static final int KEY_LENGTH = 32;

    /**
     * 时间戳长度（字节）
     */
    private static final int TIMESTAMP_LENGTH = Long.BYTES;

    /**
     * 消息ID长度（字节）
     */
    private static final int MESSAGE_ID_LENGTH = Long.BYTES;

    /**
     * 固定头部长度：时间戳 + 消息ID + enc 长度
     */
    private static final int HEADER_LENGTH = TIMESTAMP_LENGTH + MESSAGE_ID_LENGTH + 1;

    /**
     * 默认重放时间窗（毫秒），0 表示不校验
     */
    private static final long DEFAULT_REPLAY_WINDOW_MS = 5 * 60 * 1000L;

    /**
     * 重放缓存上限，超过后清理窗口外的记录
     */
    private static final int MAX_SEEN_MESSAGES = 100_000;

    /**
     * HKDF 上下文标签
     */
    private static final String HKDF_INFO = "chua-keylog-v2/aes-256-gcm";

    /**
     * 附加认证数据：绑定协议域，防止跨协议复用
     */
    private static final byte[] DOMAIN = "chua-keylog-v2".getBytes(StandardCharsets.UTF_8);

    /**
     * 摘要算法名
     */
    private static final String SHA_256 = "SHA-256";

    /**
     * 随机数发生器
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 已处理的消息ID → 时间戳；静态持有，业务每次 build 新实例也能拦住重放
     */
    private static final ConcurrentHashMap<Long, Long> SEEN_MESSAGES = new ConcurrentHashMap<>();

    /**
     * 客户端角色
     */
    private static final String ROLE_CLIENT = "client";

    /**
     * 服务端角色
     */
    private static final String ROLE_SERVER = "server";

    /**
     * 双向角色
     */
    private static final String ROLE_PAIR = "pair";

    /**
     * 公钥指纹分隔符
     */
    private static final String PIN_SEPARATOR = "#";

    /**
     * 服务端长期公钥（客户端加密用）
     */
    private PublicKey publicKey;

    /**
     * 服务端长期私钥（服务端解密用）
     */
    private PrivateKey privateKey;

    /**
     * 重放时间窗（毫秒），0 表示不校验
     */
    private long replayWindowMillis = DEFAULT_REPLAY_WINDOW_MS;

    /**
     * 生成一对长期 X25519 密钥。
     *
     * <p>公钥可公开下发；<b>私钥只应保存在服务端进程内存或受保护载体</b>，
     * 绝不写进代码、配置或日志。</p>
     *
     * @return 长度 2 数组：{@code [0]}=X.509 公钥十六进制，{@code [1]}=PKCS#8 私钥十六进制
     */
    public static String[] generateKeyPair() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance(X25519).generateKeyPair();
            return new String[]{
                    Hex.encodeHexString(pair.getPublic().getEncoded()),
                    Hex.encodeHexString(pair.getPrivate().getEncoded())};
        } catch (Exception e) {
            throw new IllegalStateException("X25519 密钥对生成失败", e);
        }
    }

    /**
     * 计算公钥指纹（SHA-256，十六进制）。
     *
     * <p>客户端把指纹钉死在配置里，服务端公钥被替换时 {@link #key(String)} 会直接拒绝。</p>
     *
     * @param publicKeyHex X.509 公钥十六进制
     * @return 指纹十六进制
     */
    public static String fingerprint(String publicKeyHex) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256).digest(Hex.decodeHex(publicKeyHex));
            return Hex.encodeHexString(digest);
        } catch (Exception e) {
            throw new IllegalArgumentException("公钥指纹计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 设置重放时间窗。
     *
     * @param millis 时间窗毫秒数，0 表示不校验时间与重复
     * @return 当前实例
     */
    public KeyLogCodec replayWindow(long millis) {
        this.replayWindowMillis = Math.max(0L, millis);
        return this;
    }

    @Override
    public Codec key(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("keylog 密钥不能为空，需 client:<公钥hex>[#指纹] / server:<私钥hex> / pair:<公钥hex>:<私钥hex>");
        }
        String[] parts = key.trim().split(":");
        try {
            KeyFactory factory = KeyFactory.getInstance(X25519);
            switch (parts[0].toLowerCase(Locale.ROOT)) {
                case ROLE_CLIENT -> {
                    requireParts(parts, 2);
                    String[] pinParts = parts[1].split(PIN_SEPARATOR, 2);
                    if (pinParts.length == 2) {
                        verifyPin(pinParts[0], pinParts[1]);
                    }
                    this.publicKey = factory.generatePublic(new X509EncodedKeySpec(Hex.decodeHex(pinParts[0])));
                }
                case ROLE_SERVER -> {
                    requireParts(parts, 2);
                    this.privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Hex.decodeHex(parts[1])));
                }
                case ROLE_PAIR -> {
                    requireParts(parts, 3);
                    this.publicKey = factory.generatePublic(new X509EncodedKeySpec(Hex.decodeHex(parts[1])));
                    this.privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Hex.decodeHex(parts[2])));
                }
                default -> throw new IllegalArgumentException("未知的 keylog 角色: " + parts[0]);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("keylog 密钥解析失败: " + e.getMessage(), e);
        }
        return this;
    }

    @Override
    public String encodeHex(String data) {
        return encodeHex(data, System.currentTimeMillis(), RANDOM.nextLong());
    }

    /**
     * 指定时间戳与消息ID加密，供测试构造过期/重放场景。
     *
     * @param data      明文
     * @param timestamp 时间戳（毫秒）
     * @param messageId 消息ID
     * @return 密文
     */
    String encodeHex(String data, long timestamp, long messageId) {
        if (data == null) {
            return null;
        }
        if (publicKey == null) {
            throw new IllegalStateException("keylog 加密需要服务端公钥（client:<公钥hex>）");
        }
        byte[] shared = null;
        byte[] ek = null;
        try {
            // 每次现场生成临时密钥对，用完即弃
            KeyPair ephemeral = KeyPairGenerator.getInstance(X25519).generateKeyPair();
            byte[] enc = ephemeral.getPublic().getEncoded();
            shared = agreement(ephemeral.getPrivate(), publicKey);
            ek = Hkdf.derive(shared, enc, HKDF_INFO, KEY_LENGTH);

            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ek, AES_ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(enc, timestamp, messageId));
            byte[] cipherText = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + enc.length + NONCE_LENGTH + cipherText.length);
            buffer.putLong(timestamp);
            buffer.putLong(messageId);
            buffer.put((byte) enc.length);
            buffer.put(enc);
            buffer.put(nonce);
            buffer.put(cipherText);
            return PREFIX + Hex.encodeHexString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("keylog 加密失败: " + e.getMessage(), e);
        } finally {
            wipe(shared, ek);
        }
    }

    @Override
    public String decodeHex(String hex) {
        if (hex == null) {
            return null;
        }
        if (privateKey == null) {
            throw new IllegalStateException("keylog 解密需要服务端私钥（server:<私钥hex>）");
        }
        if (!hex.startsWith(PREFIX)) {
            throw new IllegalArgumentException("keylog 密文缺少 " + PREFIX + " 前缀");
        }
        byte[] shared = null;
        byte[] ek = null;
        try {
            byte[] frame = Hex.decodeHex(hex.substring(PREFIX.length()));
            if (frame.length < HEADER_LENGTH + NONCE_LENGTH + TAG_LENGTH) {
                throw new IllegalArgumentException("keylog 密文长度不足");
            }
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            long timestamp = buffer.getLong();
            long messageId = buffer.getLong();
            int encLength = buffer.get() & 0xFF;
            if (encLength == 0 || frame.length < HEADER_LENGTH + encLength + NONCE_LENGTH + TAG_LENGTH) {
                throw new IllegalArgumentException("keylog 封装密钥长度非法");
            }
            byte[] enc = new byte[encLength];
            buffer.get(enc);
            byte[] nonce = new byte[NONCE_LENGTH];
            buffer.get(nonce);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            checkReplay(timestamp, messageId);

            PublicKey ephemeralPublic = KeyFactory.getInstance(X25519)
                    .generatePublic(new X509EncodedKeySpec(enc));
            shared = agreement(privateKey, ephemeralPublic);
            ek = Hkdf.derive(shared, enc, HKDF_INFO, KEY_LENGTH);

            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ek, AES_ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(enc, timestamp, messageId));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("keylog 解密失败（私钥不匹配或数据被篡改）", e);
        } finally {
            wipe(shared, ek);
        }
    }

    /**
     * 校验时间窗与消息ID，拒绝过期消息与重放。
     *
     * @param timestamp 时间戳
     * @param messageId 消息ID
     */
    private void checkReplay(long timestamp, long messageId) {
        if (replayWindowMillis <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > replayWindowMillis) {
            throw new SecurityException("keylog 消息时间戳超出允许窗口");
        }
        Long previous = SEEN_MESSAGES.putIfAbsent(messageId, timestamp);
        if (previous != null) {
            throw new SecurityException("keylog 消息重放");
        }
        if (SEEN_MESSAGES.size() > MAX_SEEN_MESSAGES) {
            long cutoff = now - replayWindowMillis;
            SEEN_MESSAGES.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }

    /**
     * 校验公钥指纹，防止公钥被替换。
     *
     * @param publicKeyHex 公钥十六进制
     * @param expectedPin  期望指纹
     */
    private static void verifyPin(String publicKeyHex, String expectedPin) {
        byte[] expected = Hex.decodeHex(expectedPin);
        byte[] actual = Hex.decodeHex(fingerprint(publicKeyHex));
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("keylog 服务端公钥指纹不匹配，公钥可能已被替换");
        }
    }

    /**
     * 做一次 X25519 密钥协商。
     *
     * @param privateKey 本端私钥（临时或长期）
     * @param publicKey  对端公钥
     * @return 共享密钥
     * @throws Exception 协商失败
     */
    private static byte[] agreement(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance(X25519);
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    /**
     * 构造附加认证数据：协议域 + 时间戳 + 消息ID + 封装密钥。
     *
     * @param enc       封装密钥
     * @param timestamp 时间戳
     * @param messageId 消息ID
     * @return AAD
     */
    private static byte[] aad(byte[] enc, long timestamp, long messageId) {
        ByteBuffer buffer = ByteBuffer.allocate(DOMAIN.length + TIMESTAMP_LENGTH + MESSAGE_ID_LENGTH + enc.length);
        buffer.put(DOMAIN);
        buffer.putLong(timestamp);
        buffer.putLong(messageId);
        buffer.put(enc);
        return buffer.array();
    }

    /**
     * 校验密钥字段个数。
     *
     * @param parts    分段
     * @param expected 期望段数
     */
    private static void requireParts(String[] parts, int expected) {
        if (parts.length != expected) {
            throw new IllegalArgumentException("keylog 密钥格式非法，需要 " + (expected - 1) + " 段密钥材料");
        }
    }

    /**
     * 用完清零中间密钥材料。
     *
     * @param arrays 待清零的字节数组
     */
    private static void wipe(byte[]... arrays) {
        for (byte[] array : arrays) {
            if (array != null) {
                Arrays.fill(array, (byte) 0);
            }
        }
    }
}
