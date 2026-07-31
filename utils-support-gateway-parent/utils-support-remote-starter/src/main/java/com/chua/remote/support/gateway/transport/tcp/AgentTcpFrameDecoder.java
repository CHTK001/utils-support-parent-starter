package com.chua.remote.support.gateway.transport.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Agent TCP 帧解码器
 * 替换 LineBasedFrameDecoder，同时支持:
 * - 文本帧(JSON)：以 \n 分隔，发射 ByteBuf
 * - 二进制帧：以 magic 0xBF 开头，发射 BinaryFrame

 * @author CH
 */@Slf4j
public class AgentTcpFrameDecoder extends ByteToMessageDecoder {

    /** 二进制帧魔数标记 */
    private static final byte BINARY_MAGIC = (byte)0xBF;

    /**
     * channelActive
     * @param ctx 参数
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
    }

    /**
     * 解码入口：识别文本帧和二进制帧
     *
     * <p>首字节为 {@code 0xBF} 时按二进制帧解析（4 字节大端长度 + payload），
     * 否则按文本帧解析（查找 \n 分隔符）。
     *
     * @param ctx Netty 上下文
     * @param in  输入缓冲区
     * @param out 输出列表，解码后的帧对象
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) { return; }

        in.markReaderIndex();
        byte firstByte = in.readByte();
        in.resetReaderIndex();

        if (firstByte == BINARY_MAGIC) {
            // ── 二进制帧 ──
            // 格式: [1B magic][4B payloadLen(大端)][payload...]
            if (in.readableBytes() < 5) { return; }
            int payloadLen = in.getInt(1);
            // 跳过 magic 读 4 字节长度;
            int totalLen = 5 + payloadLen;
            if (in.readableBytes() < totalLen) { return; }

            in.skipBytes(5);
            byte[] payload = new byte[payloadLen];
            in.readBytes(payload);

            out.add(new BinaryFrame(payload));
        }
 else {
            // ── 文本帧 ──
            // 查找 \n
            int idx = indexOfNewline(in);
            if (idx < 0) { return; }

            int lineLen = idx - in.readerIndex();
            ByteBuf line = in.readSlice(lineLen).retain();
            in.skipBytes(1);
            // 跳过 \n;

            out.add(line);
        }
    }

    /**
     * 查找 {@code \n} 在 ByteBuf 中的位置
     *
     * @param buf ByteBuf
     * @return {@code \n} 的索引，找不到返回 -1
     */
    private static int indexOfNewline(ByteBuf buf) {
        int readerIdx = buf.readerIndex();
        int writerIdx = buf.writerIndex();
        for (int i = readerIdx; i < writerIdx; i++) {
            if (buf.getByte(i) == '\n') {
                return i;
            }
        }
        return -1;
    }
}
