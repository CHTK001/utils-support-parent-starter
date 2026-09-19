package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Redisson 应用层 处理器 — 拦截 Redis Redisson 客户端调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.redisson.command.CommandAsyncService} — writeAsync / readAsync / executorAsync（命令下发底层入口）</li>
 * </ul>
 *
 * <p>Redisson 的同步/异步命令最终均经由 {@code CommandAsyncService} 编解码下发，
 * 单点插桩即可覆盖 Bucket/映射/列表/设置/流 等全部指令。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RedissonHandler extends AbstractAppHandler {

    /**
     * 命令异步服务 类内部名
     */
    private static final String COMMAND_SERVICE_CLASS = "org/redisson/command/CommandAsyncService";

    /**
     * 命令下发方法集合
     */
    private static final String[] COMMAND_METHODS = {"writeAsync", "readAsync", "executorAsync"};

    @Override
    /** 名称 */
    public String name() {
        return "redisson-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "redisson.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.REDISSON;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.REDIS;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(COMMAND_SERVICE_CLASS, COMMAND_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object config = findField(instance, "config");
        String url = config != null ? String.valueOf(findField(config, "address")) : null;
        String host = "redis";
        int port = Protocol.REDIS.defaultPort();
        if (url != null) {
            String[] addr = url.replace("redis://", "").split(":");
            if (addr.length == 2) {
                host = addr[0];
                try {
                    port = Integer.parseInt(addr[1]);
                } catch (NumberFormatException e) {
                    port = Protocol.REDIS.defaultPort();
                }
            }
        }
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.REDIS)
                .software(Software.REDISSON)
                .host(host)
                .port(port)
                .path("/")
                .build();
    }
}