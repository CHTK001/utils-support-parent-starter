package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Dubbo 应用层 Handler — 拦截 Dubbo RPC 进出站调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.apache.dubbo.rpc.protocol.dubbo.DubboInvoker} — doInvoke（Provider 侧请求入口）</li>
 *   <li>{@code org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol} — request（Consumer 侧响应入口）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Dubbo 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DubboHandler extends AbstractAppHandler {

    /**
     * DubboInvoker 类内部名
     */
    private static final String DUBBO_INVOKER = "org/apache/dubbo/rpc/protocol/dubbo/DubboInvoker";

    /**
     * DubboProtocol 类内部名
     */
    private static final String DUBBO_PROTOCOL = "org/apache/dubbo/rpc/protocol/dubbo/DubboProtocol";

    /**
     * DubboInvoker 方法集合
     */
    private static final String[] INVOKER_METHODS = {"doInvoke"};

    /**
     * DubboProtocol 方法集合
     */
    private static final String[] PROTOCOL_METHODS = {"refer", "export", "request"};

    @Override
    public String name() {
        return "dubbo-handler";
    }

    @Override
    protected String enabledKey() {
        return "dubbo.enabled";
    }

    @Override
    protected Software software() {
        return Software.DUBBO;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.DUBBO;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(DUBBO_INVOKER, INVOKER_METHODS);
        registerAll(DUBBO_PROTOCOL, PROTOCOL_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        String host = "dubbo";
        int port = Protocol.DUBBO.defaultPort();
        Object url = findField(instance, "url");
        if (url != null) {
            String urlStr = String.valueOf(url);
            String[] seg = urlStr.split("://");
            if (seg.length == 2) {
                String[] hp = seg[1].split("/")[0].split(":");
                if (hp.length >= 1) {
                    host = hp[0];
                }
                if (hp.length == 2) {
                    try {
                        port = Integer.parseInt(hp[1]);
                    } catch (NumberFormatException e) {
                        port = Protocol.DUBBO.defaultPort();
                    }
                }
            }
        }
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.DUBBO)
                .software(Software.DUBBO)
                .host(host)
                .port(port)
                .path("/")
                .build();
    }
}