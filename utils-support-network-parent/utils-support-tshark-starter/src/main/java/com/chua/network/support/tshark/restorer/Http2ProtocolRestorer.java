package com.chua.network.support.tshark.restorer;

/**
 * HTTP/2 协议还原器。
 *
 * <p>HTTP/2 连接前言：客户端发送 PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n (24 bytes)。
 * 服务端发送 24 字节 magic + SETTINGS 帧。
 * 帧格式: length(3) + type(1) + flags(1) + streamId(4) + payload。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Http2ProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取ProtocolName */
    public String getProtocolName() {
        return "http2";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 260;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 9) {
            return false;
        }
        // HTTP/2 client preface magic
        if (rawData.length >= 24) {
            return rawData[0] == 'P' && rawData[1] == 'R' && rawData[2] == 'I'
                    && rawData[3] == ' ' && rawData[4] == '*' && rawData[5] == ' '
                    && rawData[6] == 'H' && rawData[7] == 'T' && rawData[8] == 'T' && rawData[9] == 'P';
        }
        // 或者普通帧：length(3) + type(1=HEADERS,2=DATA 等) + flags(1) + streamId(4)
        int type = rawData[3] & 0xff;
        if (type > 10) {
            return false;
        }
        long length = ((rawData[0] & 0xff) << 16) | ((rawData[1] & 0xff) << 8) | (rawData[2] & 0xff);
        return length > 0 && length < 16777216 && rawData.length >= 9;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[HTTP/2] empty";
        }
        if (rawData.length >= 24 && rawData[0] == 'P' && rawData[1] == 'R' && rawData[2] == 'I') {
            return "[HTTP/2 Client Preface] 24 bytes magic + initial SETTINGS";
        }
        if (rawData.length >= 9) {
            long length = ((rawData[0] & 0xff) << 16) | ((rawData[1] & 0xff) << 8) | (rawData[2] & 0xff);
            int type = rawData[3] & 0xff;
            int flags = rawData[4] & 0xff;
            int streamId = ((rawData[5] & 0xff) << 24) | ((rawData[6] & 0xff) << 16)
                    | ((rawData[7] & 0xff) << 8) | (rawData[8] & 0xff);
            streamId &= 0x7fffffff;
            return "[HTTP/2 Frame] type=" + type + " (" + toFrameTypeName(type) + ")"
                    + ", flags=0x" + Integer.toHexString(flags)
                    + ", streamId=" + streamId
                    + ", length=" + length;
        }
        return "[HTTP/2] unknown";
    }

    /** ToFrameTypeName */
    private static String toFrameTypeName(int type) {
        return switch (type) {
            case 0x0 -> "DATA";
            case 0x1 -> "HEADERS";
            case 0x2 -> "PRIORITY";
            case 0x3 -> "RST_STREAM";
            case 0x4 -> "SETTINGS";
            case 0x5 -> "PUSH_PROMISE";
            case 0x6 -> "PING";
            case 0x7 -> "GOAWAY";
            case 0x8 -> "WINDOW_UPDATE";
            case 0x9 -> "CONTINUATION";
            case 0xa -> "ALTSVC";
            default -> "Unknown(0x" + Integer.toHexString(type) + ")";
        };
    }
}