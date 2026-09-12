package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
* Lettuce 应用层 处理器 — 拦截 Redis Lettuce 客户端调用并生成应用语义传输记录。
*
* <p>拦截目标：</p>
* <ul>
*   <li>{@code io.lettuce.core.protocol.CommandHandler} — write / writeFlush（命令下发底层入口）</li>
* </ul>
*
* <p>Lettuce 的同步/异步命令均通过 {@code CommandHandler.write(RedisCommand)} 下发，
* 因此在此单点插桩即可覆盖 获取/设置/hset 等全部命令。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class LettuceHandler extends AbstractAppHandler {

    /**
    * 命令处理器 类内部名
     */
    private static final String COMMAND_HANDLER_CLASS = "io/lettuce/core/protocol/CommandHandler";

    /**
    * 命令下发方法集合
     */
    private static final String[] WRITE_METHODS = {"write", "writeFlush"};

    @Override
    /** 名称 */
    public String name() {
        return "lettuce-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "lettuce.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.LETTUCE;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.REDIS;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(COMMAND_HANDLER_CLASS, WRITE_METHODS);
    }

    @Override
    /** 构建Target */
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