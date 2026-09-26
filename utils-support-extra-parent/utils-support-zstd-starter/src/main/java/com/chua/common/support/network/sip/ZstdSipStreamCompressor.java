package com.chua.common.support.network.sip;

import com.chua.common.support.spi.annotations.Spi;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 基于 zstd-jni 的 SIP 流式压缩实现。
 *
 * <p>该实现使用 zstd-jni 提供的原生压缩和解压流，输出的数据可以被同一实现解压，
 * 不再以未集成的占位异常模拟压缩成功。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("zstd-sip-stream-compressor")
public class ZstdSipStreamCompressor implements SipStreamCompressor {

    /**
     * 创建 zstd 压缩输出流。
     *
     * @param out 目标输出流
     * @return zstd 压缩输出流
     * @throws IOException zstd 原生库加载失败或流创建失败时抛出
     */
    @Override
    public OutputStream wrap(OutputStream out) throws IOException {
        if (out == null) {
            throw new IOException("SIP Zstd 目标输出流不能为空");
        }
        return new ZstdOutputStream(out);
    }

    /**
     * 创建 zstd 解压输入流。
     *
     * @param in 压缩数据输入流
     * @return zstd 解压输入流
     * @throws IOException zstd 原生库加载失败或流创建失败时抛出
     */
    @Override
    public InputStream unwrap(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("SIP Zstd 输入流不能为空");
        }
        return new ZstdInputStream(in);
    }

    /**
     * 获取压缩器名称。
     *
     * @return 压缩器名称
     */
    @Override
    public String name() {
        return "zstd-sip-stream-compressor";
    }
}
