package com.chua.redis.support.command;

import com.chua.common.support.spi.annotations.Spi;
import redis.clients.jedis.Jedis;

import java.util.List;

/**
 * Redis DEL 命令处理器，删除一个或多个键。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("DEL")
public class DelCommandHandler implements RedisCommandHandler {

    @Override
    public String command() {
        return "DEL";
    }

    @Override
    public int execute(Jedis jedis, List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("DEL 至少需要一个 key");
        }
        Long removed = jedis.del(args.toArray(new String[0]));
        return removed.intValue();
    }
}