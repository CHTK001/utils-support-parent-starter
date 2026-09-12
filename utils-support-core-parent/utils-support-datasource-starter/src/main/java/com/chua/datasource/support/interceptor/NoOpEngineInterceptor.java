package com.chua.datasource.support.interceptor;

import com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor;
import com.chua.common.support.spi.annotations.Spi;

/**
* 引擎拦截器默认实现，所有回调均为空操作。
*
 * <p>作为 {@code engine-interceptor} 扩展点的兜底注册项，保证 SPI 查找
* 始终有可用实现；业务方注册更高 order 的自定义拦截器后，
* 其回调将在本实现（order 最小）之前触发。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi(value = EngineInterceptor.SPI_NAME, order = -1)
public class NoOpEngineInterceptor implements EngineInterceptor {

}
