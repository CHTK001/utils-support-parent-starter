package com.chua.remote.protocol.model;

import com.chua.remote.protocol.capability.CodecProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 被控端（Agent）注册信息。
 *
 * <p>被控端连接网关时上报：id、验证码、接入码、编码能力等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被控端唯一标识 */
    private String id;

    /** 验证码 */
    private String verifyCode;

    /** 接入码 */
    private String accessCode;

    /** 编码能力 */
    private CodecProfile encodingCapability;

    /** 运行平台（windows/linux/mac） */
    private String platform;

    /** 硬件信息（GPU/CPU 型号） */
    private String hardwareInfo;

    /** 套壳模式目标主机地址 */
    private String host;

    /** 套壳模式目标端口 */
    private int port;

    /** 套壳模式登录账号 */
    private String username;

    /** 套壳模式登录密码 */
    private String password;

    /** 被控端类型：SERVICE（服务模式）| SHELL（套壳模式） */
    private AgentType agentType;

    /** 附加信息 */
    private Map<String, String> extra;

    /**
     * 被控端类型枚举。
     */
    public enum AgentType {
        /** 服务模式：完整系统，纯自研截图+编码 */
        SERVICE,
        /** 套壳模式：Java 启动器内启动三方软件（freerdp/vnc/ssh） */
        SHELL
    }
}
