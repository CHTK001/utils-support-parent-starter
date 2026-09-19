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
     * 服务地址, 必须包含 http/https 协议, 尾部斜杠可有可无(自动规整)
     */
    private String url = "http://localhost:9090";

    /**
     * 用户名(可选, 用于 HTTP Basic 认证)
     */
    private String username;

    /**
     * 密码(可选, 配合 username 使用)
     */
    private String password;

    /**
     * 请求超时(毫秒), 同时用于连接超时与单次请求读取超时, 必须大于 0
     */
    private int timeoutMs = 5_000;
}
