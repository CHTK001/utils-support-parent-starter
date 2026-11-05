// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring RestTemplate 应用层 处理器 — 拦截 RestTemplate 调用并生成应用语义传输记录。
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

    /**
     * 名称
    */
    @Override
    public String name() {
        return "resttemplate-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "resttemplate.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.REST_TEMPLATE;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(REST_TEMPLATE, TEMPLATE_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
 // RestTemplate 目标 主机 通常从方法参数的 URL 提取，此处无法直接获取，
        // 兜底返回本机 HTTP
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.REST_TEMPLATE)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }}