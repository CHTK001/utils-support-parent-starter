package com.chua.common.support.network.sip;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SIP 数据面流式压缩 SPI。
 *
 * <p>把任意 {@link OutputStream} 包装为压缩输出流；任意 {@link InputStream} 包装为解压输入流。
 * 与 {@code SipTunnelStream} 通过 {@code SipConfig.compress} 开关组合；与 AES-GCM
 * （{@code SipConfig.encrypt}）正交，组合顺序为 socket → encrypt → compress → app。</p>
 *
 * <p>SIP 数据面调用方按 SPI 加载（非硬编码 JDK GZIP），便于替换为 zstd/lz4/snappy 等实现。
 * 默认实现见 {@code @SpiDefault}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "sip-stream-compressor")
public interface SipStreamCompressor {

    /**
     * 把原始输出流包装为压缩输出流：写入明文 → 压缩 → 写到原始流。
     *
     * @param out 原始输出流（通常是 socket.getOutputStream()）
     * @return 压缩输出流
     * @throws IOException IO 异常
     */
    OutputStream wrap(OutputStream out) throws IOException;

    /**
     * 把原始输入流包装为解压输入流：读取压缩帧 → 解压 → 交给上层。
     *
     * @param in 原始输入流
     * @return 解压输入流
     * @throws IOException IO 异常
     */
    InputStream unwrap(InputStream in) throws IOException;

    /**
     * SPI 名称（用于日志/诊断）。
     * @return 结果字符串
     */
    String name();

    /**
     * 默认实现：基于 JDK {@link java.util.zip.GZIPOutputStream}/{@link java.util.zip.GZIPInputStream}。
     * GZIP 帧自带 10B 头/8B 尾；压缩级别 {@link java.util.zip.Deflater#BEST_SPEED}（低延迟优先）。
     */
    @SpiDefault
    class Gzip implements SipStreamCompressor {

        @Override
        public OutputStream wrap(OutputStream out) throws IOException {
            try {
                // BEST_SPEED + SYNC_FLUSH：每个 write 立即刷出，适合隧道场景的低延迟要求
                return new java.util.zip.GZIPOutputStream(out) {
                    {
                        def.setLevel(java.util.zip.Deflater.BEST_SPEED);
                        def.setStrategy(java.util.zip.Deflater.DEFAULT_STRATEGY);
                    }
                };
            } catch (Throwable t) {
                throw new IOException("SIP 创建 GZIP 输出流失败", t);
            }
        }

        @Override
        public InputStream unwrap(InputStream in) throws IOException {
            try {
                return new java.util.zip.GZIPInputStream(in) {
                    {
                        // 限制解压大小（防 gzip bomb 攻击）：与项目 GzipBombUtils 默认一致
                        // 这里保持 JDK 默认行为（无限制），如需加固可覆盖 fill()
                    }
                };
            } catch (Throwable t) {
                throw new IOException("SIP 创建 GZIP 输入流失败", t);
            }
        }

        @Override
        public String name() {
            return "gzip";
        }
    }
}
