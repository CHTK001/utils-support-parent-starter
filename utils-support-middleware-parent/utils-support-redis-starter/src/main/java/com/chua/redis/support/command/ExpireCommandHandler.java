package com.chua.redis.support.command;

import com.chua.common.support.spi.annotations.Spi;
import redis.clients.jedis.Jedis;

import java.util.List;

/**
* Redis EXPIRE 命令处理器，设置键的过期时间。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("EXPIRE")
public class ExpireCommandHandler implements RedisCommandHandler {

    @Override
    public String command() {
        return "EXPIRE";
    }

    @Override
    public int execute(Jedis jedis, List<String> args) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("EXPIRE 需要 key 和 seconds");
        }
        long seconds = Long.parseLong(args.get(1));
        Long result = jedis.expire(args.getFirst(), seconds);
        return result.intValue();
    }
}