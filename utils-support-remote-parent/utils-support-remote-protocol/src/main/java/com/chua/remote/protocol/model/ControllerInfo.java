package com.chua.remote.protocol.model;

import com.chua.remote.protocol.capability.CodecProfile;
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
public class ControllerInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accessToken;
    private CodecProfile decodingCapability;
    private Credentials credentials;
    private String targetAgentId;
    private String verifyCode;
    private String controllerType;
    private Map<String, String> extra;
    private boolean reverseTunnelEnabled;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Credentials implements Serializable {

        private static final long serialVersionUID = 1L;

        private String username;
        private String password;
        private String privateKey;
        private CredentialType type;

        public enum CredentialType {
            PASSWORD,
            PRIVATE_KEY
        }
    }
}
