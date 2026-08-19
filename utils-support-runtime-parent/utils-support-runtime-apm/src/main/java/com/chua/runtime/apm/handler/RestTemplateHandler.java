package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring RestTemplate 应用层 Handler — 拦截 RestTemplate 调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.springframework.web.client.RestTemplate} — execute / exchange / getForObject / postForObject 等</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Spring Web 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RestTemplateHandler extends AbstractAppHandler {

    /**
     * RestTemplate 类内部名
     */
    private static final String REST_TEMPLATE = "org/springframework/web/client/RestTemplate";

    /**
     * RestTemplate 方法集合
     */
    private static final String[] TEMPLATE_METHODS = {
            "execute", "exchange", "getForObject", "getForEntity", "postForObject",
            "postForEntity", "put", "delete", "patchForObject", "headForHeaders",
            "optionsForAllow", "getForLocation"
    };

    @Override
    /** Name */
    public String name() {
        return "resttemplate-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "resttemplate.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.REST_TEMPLATE;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(REST_TEMPLATE, TEMPLATE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        // RestTemplate 目标 host 通常从方法参数的 URL 提取，此处无法直接获取，
        // 兜底返回本机 HTTP
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.REST_TEMPLATE)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}