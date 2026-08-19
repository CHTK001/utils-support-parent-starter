package com.chua.network.support.tshark.restorer;

/**
 * SSH 协议还原器。
 *
 * <p>SSH 协议客户端/服务端版本协商还原（如 "SSH-2.0-OpenSSH_9.x"）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SshProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取ProtocolName */
    public String getProtocolName() {
        return "ssh";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 50;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return false;
        }
        return "SSH-".equals(new String(rawData, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return "[SSH] empty";
        }
        String text = utf8(rawData).trim();
        int end = text.indexOf('\n');
        if (end > 0) {
            text = text.substring(0, end).trim();
        }
        return "[SSH Version] " + text;
    }
}