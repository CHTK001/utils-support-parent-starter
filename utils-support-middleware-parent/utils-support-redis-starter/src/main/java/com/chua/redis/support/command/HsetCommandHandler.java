package com.chua.redis.support.command;

import com.chua.common.support.spi.annotations.Spi;
import redis.clients.jedis.Jedis;

import java.util.List;

/**
* Redis HSET 命令处理器，设置 哈希 字段值。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("HSET")
public class HsetCommandHandler implements RedisCommandHandler {

    @Override
    public String command() {
        return "HSET";
    }

    @Override
    public int execute(Jedis jedis, List<String> args) {
        if (args.size() < 3) {
            throw new IllegalArgumentException("HSET 需要 key、field 和 value");
        }
        return (int) jedis.hset(args.getFirst(), args.get(1), args.get(2));
    }
}