package com.chua.network.support.tshark.restorer;

/**
 * RTSP 协议还原器。
 *
 * <p>RTSP（实时流协议）请求/响应第一行：方法行（如 DESCRIBE/PLAY/SETUP）+ URI + RTSP/1.0
 * 或响应行：RTSP/1.0 + 状态码 + 状态文本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RtspProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /**
     * 获取协议名称
    */
    public String getProtocolName() {
        return "rtsp";
    }

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 230;
    }

    @Override
    /**
     * 是否可以Restore
    */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 8) {
            return false;
        }
        String text = utf8(rawData);
        if (text.startsWith("RTSP/")) {
            return true;
        }
        String[] methods = {"DESCRIBE ", "ANNOUNCE ", "OPTIONS ", "PLAY ", "PAUSE ",
                "SETUP ", "TEARDOWN ", "GET_PARAMETER ", "SET_PARAMETER ", "REDIRECT ", "RECORD "};
        for (String m : methods) {
            if (text.startsWith(m)) {
                return true;
            }
        }
        return false;
    }

    @Override
    /**
     * Restore
    */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[RTSP] empty";
        }
        String text = utf8(rawData).trim();
        int end = text.indexOf('\n');
        if (end > 0) {
            text = text.substring(0, end).trim();
        }
        return "[RTSP] " + text;
    }
}