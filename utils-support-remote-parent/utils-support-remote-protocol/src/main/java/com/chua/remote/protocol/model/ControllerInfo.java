package com.chua.remote.protocol.model;

import com.chua.remote.protocol.capability.CodecProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 控制端（Controller）注册信息。
 *
 * <p>控制端连接网关时上报：接入令牌、解码能力、账号密码/私钥、目标被控端 id、验证码等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControllerInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 接入令牌 */
    private String accessToken;

    /** 解码能力 */
    private CodecProfile decodingCapability;

    /** 账号密码或私钥 */
    private Credentials credentials;

    /** 目标被控端 id */
    private String targetAgentId;

    /** 验证码 */
    private String verifyCode;

    /** 控制端类型 */
    private String controllerType;

    /** 附加信息 */
    private Map<String, String> extra;

    /** 是否开启反向隧道 */
    private boolean reverseTunnelEnabled;

    /**
     * 凭据模型（账号密码或私钥）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Credentials implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 账号 */
        private String username;

        /** 密码（加密后） */
        private String password;

        /** 私钥（PEM 格式，加密后） */
        private String privateKey;

        /** 凭据类型：PASSWORD | PRIVATE_KEY */
        private CredentialType type;

        public enum CredentialType {
            PASSWORD,
            PRIVATE_KEY
        }
    }
}
