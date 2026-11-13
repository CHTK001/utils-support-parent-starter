package com.chua.common.support.network.ssl;

import com.chua.common.support.codec.Decoder;
import com.chua.common.support.exception.CodecException;
import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NSS key log 解码器（编解码 SPI 实现）。
 *
 * <p>把 Wireshark/tshark 的 {@code tls.keylog_file} 文本解成结构化记录：
 * 每行 {@code <label> <client_random_hex> <secret_hex>}，TLS1.2 的 label 是
 * {@code CLIENT_RANDOM}，TLS1.3 还有 {@code CLIENT_HANDSHAKE_TRAFFIC_SECRET} 等。
 * 抓包工作台与排障脚本都需要它来判断「这份 keylog 到底覆盖了哪些会话」，
 * 所以按 utils 的编解码 SPI 注册，而不是在每个调用点手写 {@code split(" ")}。</p>
 *
 * <p>解析规则：忽略空行与 {@code #} 注释；字段不足或不是合法十六进制的行跳过，
 * 不因为一行坏数据让整份 keylog 不可用。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
@Spi({"keylog", "nss", "sslkeylog"})
public class TlsKeyLogDecoder implements Decoder<String, List<TlsKeyLogDecoder.Entry>> {

    /**
     * 十六进制字符校验：偶数位、只含 0-9a-fA-F
     *
     * @param value 值
     * @return true 表示合法
     */
    private static boolean isHex(String value) {
        if (value == null || value.isEmpty() || (value.length() & 1) == 1) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            // [P3C A20 豁免]
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解码整份 keylog 文本。
     *
     * @param input keylog 文本
     * @return 记录列表（可能为空，不会为 null）
     * @throws CodecException 输入为 null 时抛出
     */
    @Override
    public List<Entry> decode(String input) {
        if (input == null) {
            throw new CodecException("keylog 文本不能为 null");
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : input.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            String[] parts = value.split("\\s+");
            if (parts.length < 3) {
                continue;
            }
            String label = parts[0].toUpperCase(Locale.ROOT);
            String clientRandom = parts[1];
            String secret = parts[2];
            if (!isHex(clientRandom) || !isHex(secret)) {
                continue;
            }
            entries.add(new Entry(label, clientRandom, secret));
        }
        return entries;
    }

    /**
     * 是否支持指定格式。
     *
     * @param format 格式标识
     * @return true 表示支持
     */
    @Override
    public boolean supports(String format) {
        if (format == null) {
            return false;
        }
        String value = format.trim().toLowerCase(Locale.ROOT);
        return "keylog".equals(value) || "nss".equals(value) || "sslkeylog".equals(value);
    }

    /**
     * 一条 keylog 记录。
     *
     * @param label        标签（如 CLIENT_RANDOM）
     * @param clientRandom 客户端随机数（十六进制）
     * @param secret       会话密钥或 traffic secret（十六进制）
     */
    public record Entry(String label, String clientRandom, String secret) {

        /**
         * 是否是 TLS1.2 的会话主密钥行。
         *
         * @return true 表示 CLIENT_RANDOM
         */
        public boolean isTls12MasterSecret() {
            return "CLIENT_RANDOM".equalsIgnoreCase(this.label);
        }
    }
}
