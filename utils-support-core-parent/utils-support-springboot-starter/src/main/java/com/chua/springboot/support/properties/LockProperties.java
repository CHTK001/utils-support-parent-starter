package com.chua.springboot.support.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
* 分布式锁配置属性。
*
* <p>通过 {@code plugin.lock.*} 配置锁类型、公平性、等待时间与租约时间，
* 默认使用 {@code chronicle} 类型，可通过配置切换 SPI 实现。</p>
*
* @author CH
* @since 2026/08/15
 */
@Data
@ConfigurationProperties(prefix = LockProperties.PRE, ignoreInvalidFields = true)
public class LockProperties {

    /**
    * 配置前缀
     */
    public static final String PRE = "plugin.lock";

    /**
    * 是否启用分布式锁自动配置
     */
    private boolean enabled = true;

    /**
    * 锁类型标识，对应 {@code LockProvider} 的 SPI 实现
     */
    private String type = "chronicle";

    /**
    * 是否公平锁
     */
    private boolean fair;

    /**
    * 获取锁的等待时间（毫秒），小于等于 0 表示不等待
     */
    private long waitTime;

    /**
    * 锁的租约时间（毫秒），-1 表示不自动释放
     */
    private long leaseTime = -1L;
}