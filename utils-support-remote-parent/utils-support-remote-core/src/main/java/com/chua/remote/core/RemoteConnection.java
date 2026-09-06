package com.chua.remote.core;

import com.chua.remote.protocol.frame.Frame;
import lombok.Getter;
import lombok.Setter;

/**
 * 远控连接抽象。
 *
 * <p>统一表示网关端与被控端/控制端之间的 WebSocket 长连接。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
public class RemoteConnection {

    /** 连接唯一标识 */
    private String connectionId;

    /** 对端角色：GATEWAY/AGENT/CONTROLLER */
    private Role role;

    /** 连接状态 */
    private State state;

    /** 对端标识（被控端 id 或控制端 id） */
    private String peerId;

    /** 对端地址 */
    private String address;

    /** 是否加密 */
    private boolean encrypted;

    /** 协商后的编解码配置 */
    private String negotiatedCodec;

    /**
     * 连接角色枚举。
     */
    public enum Role {
        GATEWAY, AGENT, CONTROLLER
    }

    /**
     * 连接状态枚举。
     */
    public enum State {
        CONNECTING, AUTHENTICATED, ACTIVE, CLOSED
    }
}
