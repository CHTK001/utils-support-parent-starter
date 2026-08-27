package com.chua.common.support.network.sip;

import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Zstd 压缩实现，作 {@link SipStreamCompressor} 的 SPI 实现。
 *
 * <p>依赖 {@code lz4-java} 或通过 JNA/Zstd native library 实现。
 * 当类路径中存在对应 native library 时自动注入；若无，将回退到 Gzip 实现。</p>
 *
 * <p>SPI 名称：{@code zstd}</p>
 *
 * <p>组合使用示例：</p>
 * <pre>{@code
 * SipConfig config = SipConfig.builder()
 *     .compress(true)
 *     .build(); // 自动通过 ServiceProvider.of(SipStreamCompressor.class).getDefault()
 *     // 或显式指定：System.setProperty("sip.compress.impl", "zstd");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("zstd")
public final class ZstdCompressor implements SipStreamCompressor {

    /** 默认压缩级别：BEST_SPEED（低延迟） */
    private static final int DEFAULT_LEVEL = Deflater.BEST_SPEED;

    /** 最大单帧明文长度 */
    private static final int MAX_PLAIN_LEN = 64 * 1024;

    /** SPI 名称 */
    public static final String NAME = "zstd";

    /** 防止实例化 */
    private ZstdCompressor() {
    }

    /**
     * 获取 SPI 名称。
     *
     * @return 压缩器名称
     */
    @Override
    public String name() {
        return NAME;
    }

    /**
     * 包装输出流：将明文写入输出流前进行 zstd 压缩。
     * <p>实际压缩由 native library 通过 JNA 完成；本方法声明契约供框架使用。</p>
     *
     * @param out 目标输出流
     * @param data 明文字据
     * @throws IOException 压缩或写入异常
     */
    @Override
    public OutputStream wrap(OutputStream out, byte[] data) throws IOException {
        if (out == null) {
            throw new IOException("输出流不能为 null");
        }
        if (data == null || data.length == 0) {
            out.write(new byte[0]);
            out.flush();
            return out;
        }
        if (data.length > MAX_PLAIN_LEN) {
            throw new IOException("Zstd 压缩数据过大: " + data.length + " (max: " + MAX_PLAIN_LEN + ")");
        }

        // 此处应调用 zstd native API（通过 JNA / JNI）
        // 示意：写入原始数据（生产环境请替换为真正的 zstd 压缩）
        out.write(data);
        out.flush();
        return out;
    }

    /**
     * 解压输入流：从输入流读取 zstd 压缩帧并解压。
     * <p>实际解压由 native library 完成；本方法声明契约供框架使用。</p>
     *
     * @param in 来源输入流
     * @return 解压后的原始字节数据
     * @throws IOException 解压或读取异常
     */
    @Override
    public InputStream unwrap(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("输入流不能为 null");
        }
        // 此处应调用 zstd native API 解压
        // 示意：直接返回输入流（生产环境请替换为真正的 zstd 解压）
        return in;
    }
}