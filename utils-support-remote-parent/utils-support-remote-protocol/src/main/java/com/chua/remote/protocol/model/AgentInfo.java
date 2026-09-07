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

    /** 被控端模式：FORWARD（纯转发）| SHELL（套壳/自研）| PUSH（纯自研实时推送，仅监控） */
    private AgentType agentType;

    /** 附加信息 */
    private Map<String, String> extra;

    /** 是否支持桌面环境（Linux 可能无桌面/无 X 服务——headless 时屏幕采集不可行，控制端应提前感知） */
    private Boolean desktopSupported;

    /** 网关 SSH 主机（用于反向隧道——agent 主动建连到网关） */
    private String gatewaySshHost;

    /** 网关 SSH 端口 */
    private int gatewaySshPort;

    /** 网关 SSH 账号 */
    private String gatewaySshUser;

    /** 网关 SSH 密码 */
    private String gatewaySshPass;

    /**
     * 被控端模式枚举。
     */
    public enum AgentType {
        /**
         * 纯转发模式：被控端仅做会话桥接，远控能力完全由三方软件
         * （freerdp/vnc/ssh）提供，Java 侧只负责对接与转发。
         */
        FORWARD,
        /**
         * 套壳模式：Java 启动器自行拉起三方软件（如 freerdp）并转发，
         * 或在套壳基础上使用自研实现。
         */
        SHELL,
        /**
         * 纯推送模式：仅自研实现，被控端实时采集并推送画面；
         * 适用于实时画面监控，不支持键鼠远程控制。
         */
        PUSH
    }
}
