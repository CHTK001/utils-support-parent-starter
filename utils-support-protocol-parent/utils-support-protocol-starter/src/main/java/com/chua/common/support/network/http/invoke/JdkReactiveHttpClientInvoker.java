package com.chua.common.support.network.http.invoke;

import com.chua.common.support.core.annotation.Spi;

/**
 * 基于 JDK 的响应式 HTTP 客户端实现
 *
 * @author CH
 */
@Spi(value = "jdk", order = 0)
public class JdkReactiveHttpClientInvoker extends AbstractReactiveHttpClientInvoker {
}

