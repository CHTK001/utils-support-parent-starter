package com.chua.tencent.support.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 微信开放平台用户信息
 * <p>
 * 记录同一用户在不同应用下的openid和平台类型
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WechatPlatformUser {

    /**
 * @author CH
     * 平台类型
     */
    public enum PlatformType {
        /** 微信小程序 */
        MINI_APP("MINI_APP"),
        /** 微信公众号（服务号/订阅号） */
        OFFICIAL_ACCOUNT("OFFICIAL_ACCOUNT"),
        /** 微信开放平台APP */
        OPEN_APP("OPEN_APP"),
        /** 微信企业微信 */
        ENTERPRISE("ENTERPRISE");

        /** 值 */
        /** 值 */
        private final String value;

        PlatformType(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static PlatformType fromValue(String value) {
            if (value == null) {
                return null;
            }
            for (PlatformType type : values()) {
                if (type.value.equals(value)) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 用户unionid（同一开放平台下唯一）
     */
    private String unionId;

    /**
     * 应用appId
     */
    private String appId;

    /**
     * 用户在该应用下的openid
     */
    private String openId;

    /**
     * 平台类型
     */
    private PlatformType platformType;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 用户头像
     */
    private String avatar;
}
