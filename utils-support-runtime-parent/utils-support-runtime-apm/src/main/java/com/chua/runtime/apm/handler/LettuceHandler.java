package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Lettuce 应用层 Handler — 拦截 Redis Lettuce 客户端调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code io.lettuce.core.protocol.CommandHandler} — write / writeFlush（命令下发底层入口）</li>
 * </ul>
 *
 * <p>Lettuce 的同步/异步命令均通过 {@code CommandHandler.write(RedisCommand)} 下发，
 * 因此在此单点插桩即可覆盖 get/set/hset 等全部命令。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LettuceHandler extends AbstractAppHandler {

    /**
     * CommandHandler 类内部名
     */
    private static final String COMMAND_HANDLER_CLASS = "io/lettuce/core/protocol/CommandHandler";

    /**
     * 命令下发方法集合
     */
    private static final String[] WRITE_METHODS = {"write", "writeFlush"};

    @Override
    public String name() {
        return "lettuce-handler";
    }

    @Override
    protected String enabledKey() {
        return "lettuce.enabled";
    }

    @Override
    protected Software software() {
        return Software.LETTUCE;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.REDIS;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(COMMAND_HANDLER_CLASS, WRITE_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object connection = findField(instance, "connection");
        String url = connection != null ? String.valueOf(findField(connection, "url")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.REDIS)
                .software(Software.LETTUCE)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "redis")
                .port(parseUrlPort(url, Protocol.REDIS.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}