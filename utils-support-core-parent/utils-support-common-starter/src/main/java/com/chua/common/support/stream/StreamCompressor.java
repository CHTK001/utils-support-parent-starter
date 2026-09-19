package com.chua.common.support.stream;

import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 通用流式压缩 SPI 接口。
 *
 * <p>该接口不绑定任何特定协议（如 SIP），可被任何模块用于
 * 流数据的压缩与解压操作。实现类通过 {@link com.chua.common.support.spi.ServiceProvider}
 * 进行发现和加载。</p>
 *
 * <p>与 {@code SipStreamCompressor} 不同，本接口不包含 SIP 相关的上下文，
 * 专注于纯粹的流压缩能力。</p>
 *
 * <p>典型实现包括：</p>
 * <ul>
 *   <li>{@code GzipStreamCompressor} - JDK GZIP 实现</li>
 *   <li>{@code ZstdStreamCompressor} - Zstd native 实现</li>
 *   <li>{@code Lz4StreamCompressor} - LZ4 快速实现</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("stream-compressor")
public interface StreamCompressor {

    /**
     * 包装输出流：将明文写入输出流前进行压缩。
     *
     * @param out 目标输出流
     * @return 包装后的输出流
     * @throws IOException IO 异常
     */
    OutputStream wrap(OutputStream out) throws IOException;

    /**
     * 解压输入流：从输入流读取压缩帧并解压为明文。
     *
     * @param in 来源输入流
     * @return 解压后的输入流
     * @throws IOException IO 异常
     */
    InputStream unwrap(InputStream in) throws IOException;

    /**
     * SPI 名称，用于 服务提供者 发现。
     *
     * @return 压缩器名称
     */
    String name();
}
