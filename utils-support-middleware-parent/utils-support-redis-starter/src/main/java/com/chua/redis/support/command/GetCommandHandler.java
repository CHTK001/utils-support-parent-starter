package com.chua.redis.support.command;

import com.chua.common.support.spi.annotations.Spi;
import redis.clients.jedis.Jedis;

import java.util.List;

/**
   * Redis 获取 命令处理器，查询字符串键值。
 *
 * <p>返回"命中数量"语义：key 存在返回 1，不存在返回 0。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("GET")
public class GetCommandHandler implements RedisCommandHandler {

    @Override
    public String command() {
        return "GET";
    }

    @Override
    public int execute(Jedis jedis, List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("GET 需要一个 key");
        }
        return jedis.get(args.get(0)) != null ? 1 : 0;
    }
}