package com.chua.common.support.lang.algorithm.crypto;

import com.chua.common.support.spi.ServiceProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 文本加解密 SPI 的二级接口：传输（流）级加解密。
 *
 * <p>一级接口 {@link Codec} 面向单条消息（String↔十六进制），本接口在其上扩展
 * 面向已建立连接流的加解密：由实现自己决定是否需要握手。Server / Client 只需按
 * 名字解析实现，即可切换传输加密算法，不必改业务代码。</p>
 *
 * <h2>内置实现</h2>
 * <ul>
 *   <li>{@code aes-gcm}：预共享口令，AES-256-GCM 帧式流，两端配置同一口令，无需握手；</li>
 *   <li>{@code keylog}：客户端持服务端公钥、服务端持长期私钥；连接建立时握手一次交换
 *       随机会话密钥，之后复用 AES-256-GCM 帧式流。服务端无需保存会话状态。</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 服务端（响应方）：读握手帧，返回解密/加密流
 * StreamCodec server = StreamCodec.buildStream("keylog", "server:" + privateKeyHex);
 * StreamCodec.Streams streams = server.open(rawIn, rawOut, false);
 *
 * // 客户端（发起方）：写握手帧，返回加密/解密流
 * StreamCodec client = StreamCodec.buildStream("keylog", "client:" + publicKeyHex + "#" + pin);
 * StreamCodec.Streams streams = client.open(rawIn, rawOut, true);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface StreamCodec extends Codec {

    /**
     * 绑定配置参数。
     *
     * @param key 预共享口令（aes-gcm）或角色化密钥（keylog：{@code server:<私钥>} / {@code client:<公钥>[#指纹]}）
     * @return 当前实例
     */
    @Override
    StreamCodec key(String key);

    /**
     * 包装连接的原始输入输出流。
     *
     * <p>{@code initiator=true} 表示调用方是发起方（通常是客户端），实现可以在这里写握手帧；
     * 响应方（通常是服务端）负责读握手帧。无握手的算法忽略该参数。</p>
     *
     * @param in        原始输入流
     * @param out       原始输出流
     * @param initiator 是否发起方
     * @return 加解密后的流
     * @throws IOException 握手失败或算法初始化失败
     */
    Streams open(InputStream in, OutputStream out, boolean initiator) throws IOException;

    /**
     * 按类型解析传输级实现并绑定参数。
     *
     * @param type 类型名，为空时默认 aes-gcm
     * @param key  配置参数（aes-gcm：口令；keylog：{@code server:<私钥>} / {@code client:<公钥>[#指纹]}）
     * @return 传输级编解码器
     * @throws IllegalArgumentException 类型不支持或实现加载失败
     */
    static StreamCodec buildStream(String type, String key) {
        String name = type == null || type.isBlank() ? "aes-gcm" : type.trim().toLowerCase(Locale.ROOT);
        ServiceProvider<StreamCodec> provider = ServiceProvider.of(StreamCodec.class);
        if (!provider.isSupport(name)) {
            throw new IllegalArgumentException("不支持的传输加密类型: " + type);
        }
        StreamCodec codec = provider.getNewExtension(name);
        if (codec == null) {
            throw new IllegalArgumentException("传输加密实现加载失败: " + type);
        }
        return codec.key(key);
    }

    /**
     * 加解密后的输入输出流。
     *
     * @param in  解密输入流
     * @param out 加密输出流
     */
    record Streams(InputStream in, OutputStream out) {
    }
}
