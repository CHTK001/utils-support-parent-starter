package com.chua.common.support.network.server.http;

import com.chua.common.support.network.server.handler.ReactiveServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import org.jspecify.annotations.NullUnmarked;

/**
 * 反射调用的 HTTP 处理器。
 *
 * <p>将 HTTP 请求通过反射转发到目标 Bean 的方法上，支持 {@link ServerRequest} 和 {@link ServerResponse}
 * 参数的自动注入。HTTP 方法为 null 时表示匹配所有请求方法。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
public interface ReflectiveHttpDefaultServerHandler extends ReactiveServerHandler, HttpDefaultServerHandler {

}
