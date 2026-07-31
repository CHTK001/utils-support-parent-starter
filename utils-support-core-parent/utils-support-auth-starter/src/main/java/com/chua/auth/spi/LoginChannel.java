package com.chua.auth.spi;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.auth.support.LoginRequest;
import com.chua.auth.support.LoginResponse;

/**
 * 登录渠道 SPI 接口
 *
 * <p>定义统一的登录渠道契约，支持授权码登录、扫码登录、账号密码登录等。
 * 实现类通过 SPI 机制按渠道名称注册，调用方通过工厂方法获取实例。
 *
 * <p>链式调用示例：
 * <pre>{@code
 *   LoginResponse response = LoginChannel.getChannel("alipay")
 *       .login(LoginRequest.builder()
 *           .authCode("auth_code_xxx")
 *           .scene(LoginScene.MINI_APP)
 *           .build());
 * }</pre>
 *
 * @author CH
 * @since 2026/07/19
 */
@Spi
public interface LoginChannel {

    /**
     * 登录
     *
     * @param request 登录请求
     * @return 登录响应
     */
    LoginResponse login(LoginRequest request);

    /**
     * 刷新令牌
     *
     * @param refreshToken 刷新令牌
     * @return 登录响应
     */
    default LoginResponse refreshToken(String refreshToken) {
        throw new UnsupportedOperationException();
    }

    /**
     * 通过 SPI 获取登录渠道实例
     *
     * @param name 渠道名称，如 "alipay"、"wechat"
     * @return LoginChannel 实例
     */
    static LoginChannel getChannel(String name) {
        return ServiceProvider.of(LoginChannel.class)
                .getExtension(name);
    }
}
