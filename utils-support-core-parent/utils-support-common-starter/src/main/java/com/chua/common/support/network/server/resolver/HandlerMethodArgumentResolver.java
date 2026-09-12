package com.chua.common.support.network.server.resolver;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Method;

/**
* 处理器方法参数解析器 SPI。
*
* <p>用于将 HTTP 请求中的参数（Query、Path、Body、Header 等）解析为处理器方法的参数。
* 类似于 Spring MVC 的 {@code HandlerMethodArgumentResolver}。</p>
*
* @author CH
* @since 2024/12/20
 */
@Spi
public interface HandlerMethodArgumentResolver {

    /**
    * 是否支持解析该参数。
    *
    * @param method         处理器方法
    * @param parameterIndex 参数索引
    * @param request        HTTP 请求
    * @return true 表示支持
     */
    boolean isSupport(Method method, int parameterIndex, ServerRequest request);

    /**
    * 解析参数值。
    *
    * @param method         处理器方法
    * @param parameterIndex 参数索引
    * @param request        HTTP 请求
    * @param response       HTTP 响应
    * @return 解析后的参数值
     */
    Object resolve(Method method, int parameterIndex, ServerRequest request, ServerResponse response);
}
