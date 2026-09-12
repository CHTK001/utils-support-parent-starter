package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * Spring Web客户端 应用层 处理器 — 拦截 WebFlux 响应式 HTTP 客户端调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.springframework.web.reactive.function.client.DefaultWebClient} — 请求构建入口</li>
 *   <li>{@code org.springframework.web.reactive.function.client.ExchangeFunctions} — 实际交换执行</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：WebFlux 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WebClientHandler extends AbstractAppHandler {

    /**
      * 默认web客户端 类内部名
     */
    private static final String DEFAULT_WEB_CLIENT = "org/springframework/web/reactive/function/client/DefaultWebClient";

    /**
      * exchangefunctions 类内部名
     */
    private static final String EXCHANGE_FUNCTIONS = "org/springframework/web/reactive/function/client/ExchangeFunctions";

    /**
      * 默认web客户端 方法集合（请求构建）
     */
    private static final String[] BUILDER_METHODS = {"exchange", "retrieve"};

    /**
      * exchangefunctions 方法集合（HTTP 执行）
     */
    private static final String[] EXCHANGE_METHODS = {"exchange"};

    @Override
    /** 名称 */
    public String name() {
        return "webclient-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "webclient.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.WEB_CLIENT;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HTTP;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(DEFAULT_WEB_CLIENT, BUILDER_METHODS);
        registerAll(EXCHANGE_FUNCTIONS, EXCHANGE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.WEB_CLIENT)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }
}