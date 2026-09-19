package com.chua.redis.support.command;

import redis.clients.jedis.Jedis;

import java.util.List;

/**
 * Redis 命令处理器 SPI 接口。
 *
 * <p>通过 {@link com.chua.common.support.spi.ServiceProvider} 按命令名（大写）加载对应实现，
 * 例如 {@code "SET"} → 设置命令处理器，{@code "DEL"} → del命令处理器。</p>
 *
 * <p>每个处理器负责一条 Redis 命令的解析与执行，返回"受影响行数"语义的整数结果：
 * 写命令返回受影响数量，读命令返回命中数量（0 或 1）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RedisCommandHandler {

    /**
     * 获取命令名（大写），同时也是 SPI 扩展键。
     * <p>例如：{@code SET}、{@code GET}、{@code DEL}、{@code HSET}。</p>
     *
     * @return 命令名
     */
    String command();

    /**
     * 执行 Redis 命令。
     *
     * @param jedis Jedis 连接
     * @param args  命令参数（不含命令名）
     * @return 受影响行数 / 命中数量
     */
    int execute(Jedis jedis, List<String> args);
}
