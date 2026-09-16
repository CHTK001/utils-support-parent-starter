package com.chua.redis.support.command;

import com.chua.common.support.spi.annotations.Spi;
import redis.clients.jedis.Jedis;

import java.util.List;

/**
* Redis 设置 命令处理器，设置字符串键值。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("SET")
public class SetCommandHandler implements RedisCommandHandler {

    @Override
    public String command() {
        return "SET";
    }

    @Override
    public int execute(Jedis jedis, List<String> args) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("SET 需要 key 和 value");
        }
        jedis.set(args.getFirst(), args.get(1));
        return 1;
    }
}