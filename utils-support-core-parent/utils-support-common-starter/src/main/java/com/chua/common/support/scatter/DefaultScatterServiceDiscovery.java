package com.chua.common.support.scatter;

import com.chua.common.support.scatter.discovery.SeedModeDiscovery;

/**
 * 默认 scatter 服务发现实现（seed 引导模式）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultScatterServiceDiscovery extends SeedModeDiscovery {

    public DefaultScatterServiceDiscovery(ScatterSetting setting) {
        super(setting);
    }
}
