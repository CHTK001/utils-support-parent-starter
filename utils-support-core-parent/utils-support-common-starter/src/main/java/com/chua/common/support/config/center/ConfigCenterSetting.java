package com.chua.common.support.config.center;

import lombok.Builder;
import lombok.Data;


/**
 * 配置中心连接设置。
 *
 * <p>封装配置中心（如 Nacos、Apollo 等）的连接参数和运行时设置。
 * 包括服务地址、认证信息、超时配置等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class ConfigCenterSetting {

    /**
     * 配置中心服务地址。
     *
     * <p>如 http://localhost:8848。</p>
     */
    private String address;

    /**
     * 激活的配置环境/Profile。
     *
     * <p>如 dev、prod、test 等，用于区分不同环境下的配置。</p>
     */
    private String profile;

    /**
     * 认证用户名。
     */
    private String username;

    /**
     * 认证密码。
     */
    private String password;

    /**
     * 连接超时时间（毫秒）。
     *
     * <p>与配置中心建立连接的最大等待时间，默认 3000ms。</p>
     */
    @Builder.Default
    private int connectionTimeout = 3000;

    /**
     * 读取超时时间（毫秒）。
     *
     * <p>等待配置中心响应的最大时间，默认 5000ms。</p>
     */
    @Builder.Default
    /**
     * 读取超时时间（毫秒）
     */
    private int readTimeout = 5000;

    /**
     * 重试次数。
     *
     * <p>连接失败时的最大重试次数，默认 3 次。</p>
     */
    @Builder.Default
    private int retryCount = 3;

    /**
     * 是否启用 SSL。
     *
     * <p>启用时使用 HTTPS 协议连接配置中心。</p>
     */
    @Builder.Default
    private boolean sslEnabled = false;
}
