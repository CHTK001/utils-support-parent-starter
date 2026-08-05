package com.chua.common.support.objects.environment;

import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.spi.annotations.Spi;

import java.util.Collections;
import java.util.List;

/**
 * 配置源提供者 SPI，为 {@link DefaultEnvironment} 自动注册配置源。
 *
 * <p>实现类通过 SPI 机制被 {@link DefaultEnvironment} 在初始化时发现，
 * 返回的 {@link PropertySource} 列表会被自动添加到环境中。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Spi
public interface ConfigSourceProvider {

    /**
     * 获取配置源列表。
     *
     * @return 配置源列表，不会为 null
     */
    List<PropertySource> getPropertySources();
}
