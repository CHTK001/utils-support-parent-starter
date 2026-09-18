package com.chua.springboot.support.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
* 限流配置属性。
*
* <p>通过 {@code plugin.rate-limiter.*} 配置限流名称与每秒许可数，
* 默认使用 Guava 实现，QPS 为 1000。</p>
*
* @author CH
* @since 2026/08/15
 */
@Data
@ConfigurationProperties(prefix = RateLimiterProperties.PRE, ignoreInvalidFields = true)
public class RateLimiterProperties {

    /**
    * 配置前缀
    */
    public static final String PRE = "plugin.rate-limiter";

    /**
    * 是否启用限流自动配置
    */
    private boolean enabled = true;

    /**
    * 限流器名称
    */
    private String name = "default";

    /**
    * 每秒许可数（QPS）
    */
    private double permitsPerSecond = 1000D;

    /**
    * 预热时间（秒），大于 0 时启用平滑预热模式
    */
    private long warmupPeriod;
}
