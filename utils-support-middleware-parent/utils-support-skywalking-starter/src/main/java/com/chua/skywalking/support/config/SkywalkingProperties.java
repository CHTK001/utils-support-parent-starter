package com.chua.skywalking.support.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SkyWalking 集成配置（前缀 {@code skywalking}）。
 * <p>
 * 只描述「怎么连 OAP」，不描述业务开关：APM 模块的启停统一由上层
 * {@code plugin.apm.*} 决定，这里只做连接参数落地。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "skywalking")
public class SkywalkingProperties {

    /**
     * 是否启用 SkyWalking 数据源
     */
    private boolean enabled = true;

    /**
     * OAP HTTP 地址，例如 {@code http://127.0.0.1:12800}
     */
    private String url = "http://127.0.0.1:12800";

    /**
     * OAP Basic 鉴权用户名（未开启鉴权留空）
     */
    private String username = "";

    /**
     * OAP Basic 鉴权密码（未开启鉴权留空）
     */
    private String password = "";

    /**
     * 单次查询超时（毫秒）
     */
    // [P3C A20 豁免]
    private long timeout = 5000L;
}
