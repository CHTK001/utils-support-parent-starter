package com.chua.remote.support.gateway.core.detector;

import com.chua.remote.support.gateway.config.ConnectionMode;
import com.chua.remote.support.gateway.config.Protocol;
import io.netty.buffer.ByteBuf;
import lombok.Data;

/**
 * 协议探测器
 *
 * <p>通过读取 TCP 连接的前若干字节（应用层负载）来嗅探远程协议类型。
 * 支持的协议包括 SSH、VNC、RDP、HTTP/HTTPS、HTTP2、MySQL、Redis 等。
 *
 * <p>探测结果包含 {@link Protocol} 枚举和 {@link ConnectionMode}（长连接/短连接），
 * 用于指导后续的会话管理和代理转发策略。
 *
 * @author CH
 * @since 4.0.0.41
 */
public class ProtocolDetector {

    /** RDP 协议魔数（前 8 字节） */
    private static final byte[] MAGIC_RDP = {
            (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x13,
            (byte) 0x0e, (byte) 0xe0, (byte) 0x00, (byte) 0x00
    };

    /** 工具类，禁止实例化 */
    private ProtocolDetector() {}

    /**
     * 嗅探 ByteBuf 中的协议类型
     *
     * <p>从当前 readerIndex 开始匹配已知协议的魔数或特征前缀。
     * 匹配完成后恢复 readerIndex，不破坏原始的读取位置。
     *
     * @param buf 包含应用层数据的 ByteBuf
     * @return 探测结果，包含协议类型和连接模式
     */
    public static DetectResult sniff(ByteBuf buf) {
        int readable = buf.readableBytes();
        if (readable < 2) {
            return new DetectResult(Protocol.UNKNOWN, ConnectionMode.SHORT);
        }
        int ri = buf.readerIndex();
        try {
            if (readable >= 8 && matchBytes(buf, ri, MAGIC_RDP)) { return new DetectResult(Protocol.RDP, ConnectionMode.LONG); }
            if (readable >= 4 && matchAscii(buf, ri, "SSH-")) { return new DetectResult(Protocol.SSH, ConnectionMode.LONG); }
            if (readable >= 4 && (matchAscii(buf, ri, "RFB ") || matchAscii(buf, ri, "RFB")))
                return new DetectResult(Protocol.VNC, ConnectionMode.LONG);

            if (readable >= 4) {
                byte b0 = buf.getByte(ri);
                if ((b0 == 'G' && buf.getByte(ri + 1) == 'E' && buf.getByte(ri + 2) == 'T') ||
                    (b0 == 'P' && buf.getByte(ri + 1) == 'O' && buf.getByte(ri + 2) == 'S' && buf.getByte(ri + 3) == 'T') ||
                    (b0 == 'P' && buf.getByte(ri + 1) == 'U' && buf.getByte(ri + 2) == 'T') ||
                    (b0 == 'D' && buf.getByte(ri + 1) == 'E' && buf.getByte(ri + 2) == 'L') ||
                    (b0 == 'H' && buf.getByte(ri + 1) == 'E' && buf.getByte(ri + 2) == 'A' && buf.getByte(ri + 3) == 'D') ||
                    (b0 == 'P' && buf.getByte(ri + 1) == 'A' && buf.getByte(ri + 2) == 'T' && buf.getByte(ri + 3) == 'C') ||
                    (b0 == 'O' && buf.getByte(ri + 1) == 'P' && buf.getByte(ri + 2) == 'T' && buf.getByte(ri + 3) == 'I'))
                    return new DetectResult(Protocol.HTTP, ConnectionMode.SHORT);
            }
            if (readable >= 10 && matchAscii(buf, ri, "PRI * HTTP/2")) { return new DetectResult(Protocol.HTTP2, ConnectionMode.SHORT); }
            if (readable >= 3 && buf.getByte(ri) == 0x16 && buf.getByte(ri + 1) >= 0x03) { return new DetectResult(Protocol.HTTPS, ConnectionMode.SHORT); }
            if (readable >= 4 && buf.getByte(ri) == 0x10 && buf.getByte(ri + 1) == 0x00 &&
                buf.getByte(ri + 2) == 0x00 && buf.getByte(ri + 3) == 0x00) { return new DetectResult(Protocol.MYSQL, ConnectionMode.SHORT); }
            if ((readable >= 1 && (buf.getByte(ri) == '*' || buf.getByte(ri) == '+' ||
                 buf.getByte(ri) == '-' || buf.getByte(ri) == ':' || buf.getByte(ri) == '$')) ||
                (readable >= 4 && (matchAscii(buf, ri, "PING") || matchAscii(buf, ri, "AUTH") ||
                 matchAscii(buf, ri, "INFO") || matchAscii(buf, ri, "SET ") || matchAscii(buf, ri, "GET "))))
                return new DetectResult(Protocol.REDIS, ConnectionMode.SHORT);
            return new DetectResult(Protocol.UNKNOWN, ConnectionMode.SHORT);
        }
 finally {
            buf.readerIndex(ri);
        }
    }

    /**
     * 逐字节匹配魔数
     *
     * @param buf    ByteBuf
     * @param offset 起始偏移
     * @param magic  魔数字节数组
     * @return 完全匹配返回 true
     */
    private static boolean matchBytes(ByteBuf buf, int offset, byte[] magic) {
        for (int i = 0; i < magic.length; i++) {
            if (buf.getByte(offset + i) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * ASCII 字符串前缀匹配
     *
     * @param buf    ByteBuf
     * @param offset 起始偏移
     * @param str    待匹配的 ASCII 字符串
     * @return 完全匹配返回 true
     */
    private static boolean matchAscii(ByteBuf buf, int offset, String str) {
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (buf.readableBytes() - offset < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (buf.getByte(offset + i) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 协议探测结果
     *
     * <p>包含识别出的 {@link Protocol} 以及建议的 {@link ConnectionMode}。
     */
    @Data
    public static class DetectResult {
        /** 识别出的协议类型 */
        private final Protocol protocol;
        /** 建议的连接模式（长连接/短连接） */
        private final ConnectionMode mode;
    }
}
