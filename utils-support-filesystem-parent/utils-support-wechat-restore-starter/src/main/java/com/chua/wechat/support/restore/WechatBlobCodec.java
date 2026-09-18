package com.chua.wechat.support.restore;

import com.github.luben.zstd.Zstd;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

/**
 * 微信消息体 blob 解码器。
 *
 * <p>微信 4.x 会把<b>消息正文与 {@code source} 用 ZSTD 压缩后直接存进列里</b>，
 * 在内存页的记录中表现为 {@code blob[N]}。不解压的话，聊天记录里大半正文只剩
 * 「二进制」三个字。</p>
 *
 * <p>解码策略：按魔数自动识别（ZSTD / zlib / gzip），解压后按
 * <b>可打印字符占比 ≥ 90%</b> 判定是否当文本输出 —— 图片、语音等二进制内容
 * 即使解压成功也不会被误当成文本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatBlobCodec {

    /**
    * ZSTD 魔数（小端存储，对应字节序列 28 B5 2F FD）
    */
    private static final int ZSTD_MAGIC = 0xFD2FB528;

    /**
    * zlib 头（CMF/FLG，低 4 位为 8 表示 deflate）
    */
    private static final int ZLIB_METHOD_MASK = 0x0F;

    /**
    * 单次解压的最大输出字节数（4MB），防止被损坏数据撑爆内存
    */
    private static final int MAX_DECOMPRESS = 4 << 20;

    /**
    * 判定为文本的可打印字符占比阈值
    */
    private static final double TEXT_RATIO = 0.9;

    /**
     * 构造方法，创建 WechatBlobCodec 实例。
     */
    private WechatBlobCodec() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
    * 把 blob 描述成可读文本。
    *
    * @param blob 原始字节
    * @return 解压成功且是文本时返回文本；否则返回 {@code blob[N...]} 形式的占位描述
    */
    public static String describe(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return "blob[0]";
        }
        if (isZstd(blob)) {
            byte[] out = zstdDecompress(blob);
            if (out != null) {
                return render(out, "zstd", blob.length);
            }
        }
        byte[] inflated = inflate(blob);
        if (inflated != null) {
            return render(inflated, "inflate", blob.length);
        }
        return "blob[" + blob.length + "]";
    }

    /**
    * 尝试把 blob 解压成文本。
    *
    * <p>与 {@link #describe(byte[])} 的区别：本方法在「解压失败」或「解压结果是二进制」时
    * 返回 {@code null}，让调用方自行决定如何降级（例如回退成十六进制）。</p>
    *
    * @param blob 原始字节
    * @return 文本；解压失败或非文本返回 null
    */
    public static String tryDecompressText(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return null;
        }
        if (isZstd(blob)) {
            byte[] out = zstdDecompress(blob);
            if (out != null) {
                String text = new String(out, StandardCharsets.UTF_8);
                if (isMostlyText(text)) {
                    return sanitize(text);
                }
            }
        }
        byte[] inflated = inflate(blob);
        if (inflated != null) {
            String text = new String(inflated, StandardCharsets.UTF_8);
            if (isMostlyText(text)) {
                return sanitize(text);
            }
        }
        return null;
    }

    /**
    * 是否为 ZSTD 压缩数据。
    *
    * @param data 字节
    * @return 是 ZSTD 返回 true
    */
    public static boolean isZstd(byte[] data) {
        return data != null && data.length >= 4
                && (data[0] & 0xFF) == (ZSTD_MAGIC & 0xFF)
                && (data[1] & 0xFF) == ((ZSTD_MAGIC >>> 8) & 0xFF)
                && (data[2] & 0xFF) == ((ZSTD_MAGIC >>> 16) & 0xFF)
                && (data[3] & 0xFF) == ((ZSTD_MAGIC >>> 24) & 0xFF);
    }

    /**
    * ZSTD 解压。
    *
    * @param src 压缩数据
    * @return 解压结果；失败返回 null
    */
    private static byte[] zstdDecompress(byte[] src) {
        try {
            long size = Zstd.decompressedSize(src);
            // decompressedSize 对「未写入 content size」的帧不可靠（可能返回 0 或偏小），
            // 因此解压失败时按倍数放大缓冲区重试，而不是直接放弃
            int capacity = (size > 0 && size <= MAX_DECOMPRESS) ? (int) size : (1 << 20);
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    return Zstd.decompress(src, capacity);
                } catch (Throwable t) {
                    if (capacity >= MAX_DECOMPRESS) {
                        return null;
                    }
                    capacity = Math.min(MAX_DECOMPRESS, capacity * 4);
                }
            }
            return null;
        } catch (Throwable t) {
            log.debug("ZSTD 解压失败: {}", t.getMessage());
            return null;
        }
    }

    /**
    * zlib / gzip 解压。
    *
    * @param src 压缩数据
    * @return 解压结果；失败返回 null
    */
    private static byte[] inflate(byte[] src) {
        if (src.length < 2 || (src[0] & ZLIB_METHOD_MASK) != 8) {
            return null;
        }
        Inflater inflater = new Inflater(src[0] != (byte) 0x78 || (src[1] & 0xFF) == 0x9C);
        try {
            inflater.setInput(src);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(src.length * 4, MAX_DECOMPRESS));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                out.write(buffer, 0, n);
                if (out.size() > MAX_DECOMPRESS) {
                    return null;
                }
            }
            return out.size() == 0 ? null : out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /**
    * 把解压结果渲染成文本或占位描述。
    *
    * @param out    解压结果
    * @param how    解压方式名
    * @param srcLen 原始长度
    * @return 文本或占位描述
    */
    private static String render(byte[] out, String how, int srcLen) {
        String text = new String(out, StandardCharsets.UTF_8);
        if (isMostlyText(text)) {
            return sanitize(text);
        }
        return "blob[" + srcLen + "→" + how + " " + out.length + "B 非文本]";
    }

    /**
    * 判定字符串是否以可打印字符为主。
    *
    * @param s 字符串
    * @return 可打印字符占比 ≥ 90% 返回 true
    */
    private static boolean isMostlyText(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int printable = 0;
        int total = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') {
                return false;
            }
            total++;
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && c != 0x7F)) {
                printable++;
            }
        }
        return total > 0 && (double) printable / total >= TEXT_RATIO;
    }

    /**
    * 清理文本中的控制字符。
    *
    * <p>字段分隔符用的是 SOH（{@code \u0001}），这里把所有 {@code < 0x20} 的控制字符
    * 统一替换成空格，保证值里不可能再出现分隔符。</p>
    *
    * @param s 原文本
    * @return 清理后的文本
    */
    public static String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 ? ' ' : c);
        }
        return sb.toString();
    }
}
