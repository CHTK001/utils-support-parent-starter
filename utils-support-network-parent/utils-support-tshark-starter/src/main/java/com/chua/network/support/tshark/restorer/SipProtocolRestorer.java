package com.chua.network.support.tshark.restorer;

/**
* SIP 协议还原器。
*
* <p>SIP（会话初始协议）请求/响应第一行：方法 + URI + SIP/2.0
* 或响应行：SIP/2.0 + 状态码 + 状态文本。
* 识别 INVITE/注册/BYE/CANCEL/ACK/期权/PRACK/信息/订阅/通知/REFER 等方法。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class SipProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "sip";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 240;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return false;
        }
        String text = utf8(rawData);
        if (text.startsWith("SIP/2.0")) {
            return true;
        }
        String[] methods = {"INVITE ", "REGISTER ", "BYE ", "CANCEL ", "ACK ",
                "OPTIONS ", "PRACK ", "INFO ", "SUBSCRIBE ", "NOTIFY ", "REFER ", "MESSAGE ", "UPDATE "};
        for (String m : methods) {
            if (text.startsWith(m)) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[SIP] empty";
        }
        String text = utf8(rawData).trim();
        int end = text.indexOf('\n');
        if (end > 0) {
            text = text.substring(0, end).trim();
        }
        return "[SIP] " + text;
    }
}