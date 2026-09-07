package com.chua.remote.protocol.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 远控会话模型。
 *
 * <p>一个会话关联一个控制端和一个被控端，由网关统一管理生命周期。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话标识 */
    private String sessionId;

    /** 控制端会话 id */
    private String controllerSessionId;

    /** 被控端 id */
    private String agentId;

    /** 会话状态 */
    private SessionStatus status;

    /** 协商后的编解码配置 */
    private NegotiatedCodec negotiatedCodec;

    /** 被控端模式（网关在建立会话时从被控端注册信息填充） */
    private AgentInfo.AgentType agentType;

    /** 创建时间戳 */
    private long createTime;

    /** 过期时间戳 */
    private Long expireTime;

    /** 附加元数据 */
    private Map<String, String> metadata;

    /**
     * 会话状态枚举。
     */
    public enum SessionStatus {
        /** 建立中 */
        CONNECTING,
        /** 已建立 */
        ACTIVE,
        /** 转码中 */
        TRANSCODING,
        /** 已关闭 */
        CLOSED
    }

    /**
     * 协商后的编解码配置。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NegotiatedCodec implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 最终编码格式 */
        private String encoding;

        /** 最终宽度 */
        private int width;

        /** 最终高度 */
        private int height;

        /** 最终质量 */
        private int quality;

        /** 是否需要网关转码 */
        private boolean transcoded;
    }
}
