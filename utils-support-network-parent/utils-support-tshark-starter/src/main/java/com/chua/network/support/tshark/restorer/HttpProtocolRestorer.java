package com.chua.network.support.tshark.restorer;

/**
 * HTTP 协议还原器。
 *
 * <p>将 HTTP 请求/响应字节还原为可读文本，包含请求行、headers、body。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HttpProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "http";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 10;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return false;
        }
        String text = utf8(rawData);
        return text.startsWith("HTTP/")
                || text.startsWith("GET ")
                || text.startsWith("POST ")
                || text.startsWith("PUT ")
                || text.startsWith("DELETE ")
                || text.startsWith("HEAD ")
                || text.startsWith("OPTIONS ")
                || text.startsWith("PATCH ");
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "[HTTP] empty payload";
        }
        String text = utf8(rawData);
        StringBuilder sb = new StringBuilder();
        sb.append("[HTTP] ").append(text.length()).append(" bytes\n");
        String[] lines = text.split("\\r?\\n");
        int headerEnd = -1;
        for (int i = 0; i < lines.length; i++) {
            sb.append("  ").append(lines[i]).append('\n');
            if (lines[i].isEmpty() && headerEnd < 0) {
                headerEnd = i;
                break;
            }
        }
        if (headerEnd < 0) {
            return sb.toString();
        }
        StringBuilder body = new StringBuilder();
        for (int i = headerEnd + 1; i < lines.length; i++) {
            body.append(lines[i]).append('\n');
        }
        String bodyStr = body.toString().trim();
        if (!bodyStr.isEmpty()) {
            int maxLen = 512;
            if (bodyStr.length() > maxLen) {
                bodyStr = bodyStr.substring(0, maxLen) + "...(truncated)";
            }
            sb.append("  [BODY]\n").append(bodyStr).append('\n');
        }
        return sb.toString();
    }
}