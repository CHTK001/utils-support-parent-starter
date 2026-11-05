// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * OkHttp 处理器 — intercepts OkHttp call 执行.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OkHttpHandler extends AbstractAppHandler {

    /**
     * CALL
     */
    private static final String CALL = "okhttp3/Call";
    /**
     * 执行 方法
     */
    private static final String[] EXECUTE_METHODS = {"execute", "enqueue"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "okhttp-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "okhttp.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.OKHTTP;
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
        registerAll(CALL, EXECUTE_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HTTP)
                .software(Software.OKHTTP)
                .host("http")
                .port(80)
                .path("/")
                .build();
    }}