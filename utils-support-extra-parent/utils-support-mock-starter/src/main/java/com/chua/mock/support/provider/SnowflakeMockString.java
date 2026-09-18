package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 雪花 标识 Mock 生成器
*
* <p>模拟雪花算法生成 19 位长整型 ID 字符串：
* 时间戳（41 位）+ 机器标识 + 序列号，单调递增且全局唯一。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"snowflake", "snowflake-id"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class SnowflakeMockString implements MockString {

    /**
    * 上次时间戳
    */
    private long lastTimestamp = -1L;
    /**
    * 序列号
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private long sequence = 0L;

    @Override
    @Nonnull
    public synchronized String getString(@Nonnull MockEnvironment environment) {
        long timestamp = System.currentTimeMillis();
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & 0xFFF;
            if (sequence == 0) {
                timestamp = waitNextMillis(timestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = timestamp;
        long machineId = environment.nextInt(1024);
        long id = ((timestamp - 1420070400000L) << 22) | (machineId << 12) | sequence;
        return Long.toString(id);
    }

    /**
    * 等待到下一毫秒。
    *
    * @param last 当前时间戳
    * @return 下一毫秒时间戳
    */
    private long waitNextMillis(long last) {
        long time = System.currentTimeMillis();
        while (time <= last) {
            time = System.currentTimeMillis();
        }
        return time;
    }
}
