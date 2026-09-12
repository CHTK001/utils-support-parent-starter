package com.chua.common.support.network.sip;

import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Zstd SIP 数据面流式压缩实现。
 *
 * <p>使用 Zstd 原生库进行高效压缩与解压，适合对压缩比和速度有平衡要求的隧道传输场景。</p>
 *
 * <p>与 {@link SipStreamCompressor} SPI 契约一致，通过 {@code SipConfig.compress} 开关启用。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("zstd-sip-stream-compressor")
public class ZstdSipStreamCompressor implements SipStreamCompressor {

    @Override
    public OutputStream wrap(OutputStream out) throws IOException {
        try {
 // TODO: 集成 Zstd NAT 图书馆（JNI 或 zstd-jni）创建压缩输出流
 // 示例占位：实际需替换为 zstd输出流 或 packed输出流
            throw new IOException("Zstd native library not integrated yet; use GZIP as fallback");
        } catch (Throwable t) {
            throw new IOException("SIP Zstd 包装输出流失败", t);
        }
    }

    @Override
    public InputStream unwrap(InputStream in) throws IOException {
        try {
 // TODO: 集成 Zstd NAT 图书馆 创建解压输入流
 // 示例占位：实际需替换为 zstd输入流 或 packed输入流
            throw new IOException("Zstd native library not integrated yet; use GZIP as fallback");
        } catch (Throwable t) {
            throw new IOException("SIP Zstd 解压输入流失败", t);
        }
    }

    @Override
    public String name() {
        return "zstd-sip-stream-compressor";
    }
}