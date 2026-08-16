package com.chua.prometheus.support.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prometheus 配置属性
 * <p>
 * 前缀 {@code prometheus}, 自动装配时用于创建默认客户端。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@ConfigurationProperties(prefix = "prometheus")
public class PrometheusProperties {

    /**
     * 是否启用
     */
    private boolean enabled = true;

    /**
     * 服务地址
     */
    private String url = "http://localhost:9090";

    /**
     * 用户名(可选, 用于 Basic Auth)
     */
    private String username;

    /**
     * 密码(可选)
     */
    private String password;

    /**
     * 请求超时(毫秒)
     */
    private int timeoutMs = 5_000;
}