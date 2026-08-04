package com.chua.common.support.network.server.http;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import org.jspecify.annotations.NullUnmarked;

/**
 * HTTP 路由处理器接口。
 *
 * <p>该接口专为 HTTP 协议设计，继承自通用的 {@link ServerHandler}，
 * 同时允许定义请求路径（path）、HTTP 方法（method）及响应头（headers）等元数据，
 * 便于在 IOC 容器中自动发现并注册路由处理逻辑。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
@FunctionalInterface
public interface HttpDefaultServerHandler extends ServerHandler {

    /**
     * 获取该处理器匹配的路径模式。默认为空字符串。
     *
     * @return 路径字符串
     */
    default String path() {
        return "";
    }

    /**
     * 获取该处理器匹配的 HTTP 方法。默认为 GET。
     *
     * @return HTTP 方法枚举
     */
    default HttpMethod method() {
        return HttpMethod.GET;
    }

    /**
     * 获取该处理器需要的请求头配置。默认为空。
     *
     * @return HTTP 头集合
     */
    default HttpHeader headers() {
        return HttpHeader.create();
    }

    /**
     * 获取处理器的描述信息，默认使用类名。
     *
     * @return 描述字符串
     */
    default String description() {
        return getClass().getSimpleName();
    }

}