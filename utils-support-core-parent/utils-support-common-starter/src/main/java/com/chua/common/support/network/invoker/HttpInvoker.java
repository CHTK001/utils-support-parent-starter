package com.chua.common.support.network.invoker;

import com.chua.common.support.network.client.HttpApiFactory;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 基于 {@link HttpApiFactory} 的默认 HTTP 调用器实现。
 *
 * <p>使用项目自有的声明式 HTTP 客户端框架，通过 JDK 动态代理将接口方法调用
 * 转换为 HTTP 请求。支持 Spring MVC 注解和 {@code @RequestMethod} 注解。</p>
 *
 * <p>SPI 名称为 {@code "http"}，order=0 作为默认兜底实现。
 * 第三方集成（如 Retrofit）可通过 SPI 提供更高优先级的实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see HttpApiFactory
 * @see Invoker
 */
@Spi(value = "http", order = 0)
public class HttpInvoker implements Invoker {

    @Override
    /** 创建 */
    public <T> T create(Class<T> apiClass) {
        return HttpApiFactory.create(apiClass);
    }

    @Override
    /** 创建New */
    public <T> T createNew(Class<T> apiClass) {
        return HttpApiFactory.createNew(apiClass);
    }
}