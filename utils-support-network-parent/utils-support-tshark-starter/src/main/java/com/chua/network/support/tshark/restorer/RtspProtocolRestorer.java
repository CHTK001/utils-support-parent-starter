// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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

    /**
     * 获取协议名称
    */
    @Override
    public String getProtocolName() {
        return "rtsp";
    }

    /**
     * 获取Priority
    */
    @Override
    public int getPriority() {
        return 230;
    }

    /**
     * 是否可以Restore
    */
    @Override
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

    /**
     * Restore
    */
    @Override
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
    }}