package com.chua.remote.protocol.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sessionId;
    private String controllerSessionId;
    private String agentId;
    private SessionStatus status;
    private NegotiatedCodec negotiatedCodec;
    private AgentInfo.AgentType agentType;
    private long createTime;
    private Long expireTime;
    private Map<String, String> metadata;
    private boolean reverseTunnelEnabled;

    public enum SessionStatus {
        CONNECTING,
        ACTIVE,
        TRANSCODING,
        CLOSED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NegotiatedCodec implements Serializable {

        private static final long serialVersionUID = 1L;

        private String encoding;
        private String targetEncoding;
        private int width;
        private int height;
        private int quality;
        private boolean transcoded;
    }
}
