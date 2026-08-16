package com.chua.auth.support;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 登录请求
 *
 * <p>通过 Builder 模式构建，支持链式调用。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    /**
     * 授权码（OAuth2 authorization_code 或微信 code）
     */
    private String authCode;

    /**
     * 登录场景
     */
    private LoginScene scene;

    /**
     * 扩展参数
     */
    private Map<String, String> extParams;
}
