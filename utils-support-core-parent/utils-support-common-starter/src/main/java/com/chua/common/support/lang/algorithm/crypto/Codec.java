package com.chua.common.support.lang.algorithm.crypto;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Locale;

/**
 * 文本加解密 SPI。
 *
 * <p>所有算法实现都通过 {@code META-INF/extensions/} 注册，按类型名解析：
 * {@code aes}（AES-256-CBC，与前端请求体协议对齐）、{@code aes-gcm}（AES-256-GCM，落库用）、
 * {@code sm4}、{@code des}、{@code hex}（仅编码，不是加密）、
 * {@code keylog}（X25519 临时密钥交换 + HKDF + AES-256-GCM，每次随机会话密钥、服务端凭长期私钥无状态解密）。</p>
 *
 * <p><b>不认识的类型直接抛异常</b>，不会静默退化成十六进制编码；这也是「假加密」在
 * 代码层的最后一道拦截：配置写错时立刻失败，而不是把明文当密文发出去。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * Codec codec = Codec.build("sm4", "1234567890abcdef");
 * String cipher = codec.encodeHex("明文");
 * String plain = codec.decodeHex(cipher);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0
 */
public interface Codec {

    /**
     * 绑定密钥，返回可用的编解码器。
     *
     * @param key 密钥原文（对称算法）或公钥十六进制（SM2 不走本接口）
     * @return 绑定密钥后的编解码器
     */
    Codec key(String key);

    /**
     * 编码为十六进制字符串。
     *
     * @param data 明文
     * @return 十六进制密文
     */
    String encodeHex(String data);

    /**
     * 解码十六进制字符串。
     *
     * @param hex 十六进制密文
     * @return 明文
     */
    String decodeHex(String hex);

    /**
     * 按类型解析实现。
     *
     * @param type 类型名，如 aes / aes-gcm / sm4 / des / hex
     * @param key  密钥
     * @return 编解码器
     * @throws IllegalArgumentException 类型不支持
     */
    static Codec build(String type, String key) {
        String name = type == null || type.isBlank() ? "aes" : type.trim().toLowerCase(Locale.ROOT);
        ServiceProvider<Codec> provider = ServiceProvider.of(Codec.class);
        if (!provider.isSupport(name)) {
            throw new IllegalArgumentException("不支持的加解密类型: " + type);
        }
        Codec codec = provider.getNewExtension(name);
        if (codec == null) {
            throw new IllegalArgumentException("加解密实现加载失败: " + type);
        }
        return codec.key(key);
    }
}
