package com.chua.redis.support.command;

import com.chua.common.support.spi.annotations.Spi;
import redis.clients.jedis.Jedis;

import java.util.List;

/**
* Redis INCR 命令处理器，将 键 存储的数值递增 1。
*
* <p>返回递增后的新值（Long，转换为 int）。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("INCR")
public class IncrCommandHandler implements RedisCommandHandler {

    @Override
    public String command() {
        return "INCR";
    }

    @Override
    public int execute(Jedis jedis, List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("INCR 需要一个 key");
        }
        Long value = jedis.incr(args.getFirst());
        return value.intValue();
    }
}