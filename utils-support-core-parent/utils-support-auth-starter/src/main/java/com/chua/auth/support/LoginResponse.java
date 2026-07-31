package com.chua.auth.support;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 登录响应
 *
 * @author CH
 * @since 2026/07/19
 */
@Data
@Builder
public class LoginResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 平台用户标识（支付宝 user_id / 微信 openid / unionid）
     */
    private String userId;

    /**
     * 访问令牌
     */
    private String accessToken;

    /**
     * 刷新令牌
     */
    private String refreshToken;

    /**
     * 过期时间（秒）
     */
    private Long expiresIn;

    /**
     * 原始响应体
     */
    private String rawResponse;

    /**
     * 扩展参数
     */
    private Map<String, String> extParams;
}
