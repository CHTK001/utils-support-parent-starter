package com.chua.common.support.lang.algorithm;

import java.util.concurrent.ThreadLocalRandom;

/**
* 雪花算法 ID 生成器，分布式全局唯一 ID 生成算法。
*
* <p>64 位 Long 型 ID 的位分配如下（可自定义）：</p>
* <pre>
*   1 bit sign   |  41 bits timestamp  |  10 bits workerId  |  12 bits sequence
*   (始终为 0)   |  (相对自定义纪元)    |  (机器节点 ID)     |  (自增序号)
* </pre>
*
* <ul>
*   <li>41 位时间戳：可使用约 69 年（相对于纪元起始时间）</li>
*   <li>10 位工作节点 ID：最多支持 1024 个节点</li>
*   <li>12 位序列号：同一毫秒内最多生成 4096 个 ID</li>
*   <li>整体支持 {@code 1024 * 4096 = 4194304} ID/ms 的吞吐量</li>
* </ul>
*
* <p>使用方法：</p>
* <pre>{@code
* // 使用默认配置创建（工作节点 ID 为 0）
* SnowflakeIdGenerator generator = new SnowflakeIdGenerator();
*
* // 指定工作节点 ID
* SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);
*
* // 生成 ID
* long id = generator.nextId();
* String idStr = generator.nextIdString();
* }</pre>
*
* @author CH
* @since 1.0.0
 */
public class SnowflakeIdGenerator {

    // ==================== 默认位分配 ====================

    /** 时间戳占用位数 */
    private static final long DEFAULT_TIMESTAMP_BITS = 41L;

    /** 工作节点 ID 占用位数 */
    private static final long DEFAULT_WORKER_ID_BITS = 10L;

    /** 序列号占用位数 */
    private static final long DEFAULT_SEQUENCE_BITS = 12L;

    // ==================== 移位偏移量 ====================

    /** 序列号移位偏移 */
    private static final long SEQUENCE_SHIFT = 0L;

    /** 工作节点 ID 移位偏移 */
    private final long workerIdShift;

    /** 时间戳移位偏移 */
    private final long timestampShift;

    // ==================== 掩码 ====================

    /** 序列号掩码（用于截断到指定位数） */
    private final long sequenceMask;

    /** 工作节点 ID 掩码 */
    private final long workerIdMask;

    // ==================== 默认值 ====================

    /** 默认纪元起始时间（2020-01-01 00:00:00 UTC，单位毫秒） */
    private static final long DEFAULT_EPOCH = 1577836800000L;

    /** 默认工作节点 ID */
    private static final long DEFAULT_WORKER_ID = 0L;

    // ==================== 实例状态 ====================

    /** 纪元起始时间（毫秒） */
    private final long epoch;

    /** 工作节点 ID */
    private final long workerId;

    /** 上次生成 ID 的时间戳（毫秒） */
    private volatile long lastTimestamp = -1L;

    /** 当前毫秒内的序列号 */
    private volatile long sequence = 0L;

    /** 序列号同步锁 */
    private final Object lock = new Object();

    // ==================== 构造方法 ====================

    /**
    * 使用默认配置创建雪花算法 ID 生成器
    *
    * <p>默认工作节点 ID 为 0，纪元起始时间为 2020-01-01。</p>
     */
    public SnowflakeIdGenerator() {
        this(DEFAULT_WORKER_ID, DEFAULT_EPOCH, DEFAULT_WORKER_ID_BITS, DEFAULT_TIMESTAMP_BITS, DEFAULT_SEQUENCE_BITS);
    }

    /**
    * 使用指定的工作节点 ID 创建雪花算法 ID 生成器
    *
    * @param workerId 工作节点 ID（0 ~ {@code 2^workerIdBits - 1}）
    * @throws IllegalArgumentException 当工作节点 ID 超出范围时
     */
    public SnowflakeIdGenerator(long workerId) {
        this(workerId, DEFAULT_EPOCH, DEFAULT_WORKER_ID_BITS, DEFAULT_TIMESTAMP_BITS, DEFAULT_SEQUENCE_BITS);
    }

    /**
    * 使用完全自定义的位分配创建雪花算法 ID 生成器
    *
    * @param workerId           工作节点 ID
    * @param epoch              纪元起始时间（毫秒）
    * @param workerIdBits       工作节点 ID 占用位数
    * @param timestampBits      时间戳占用位数
    * @param sequenceBits       序列号占用位数
    * @throws IllegalArgumentException 当参数超出范围时
     */
    public SnowflakeIdGenerator(long workerId, long epoch,
                                long workerIdBits, long timestampBits, long sequenceBits) {
        // 计算掩码
        this.workerIdMask = ~(-1L << workerIdBits);
        this.sequenceMask = ~(-1L << sequenceBits);

        // 校验工作节点 ID
        if (workerId < 0 || workerId > workerIdMask) {
            throw new IllegalArgumentException(String.format(
                    "工作节点 ID 必须介于 0 ~ %d 之间，当前值: %d", workerIdMask, workerId));
        }

        // 计算移位偏移
        this.workerIdShift = sequenceBits;
        this.timestampShift = workerIdBits + sequenceBits;

        this.workerId = workerId;
        this.epoch = epoch;
    }

    // ==================== ID 生成方法 ====================

    /**
    * 生成下一个唯一 ID
    *
    * @return 64 位 Long 型唯一 ID
    * @throws IllegalStateException 如果系统时钟回拨（时钟倒退）
     */
    public long nextId() {
        synchronized (lock) {
            long currentTimestamp = timestamp();

            if (currentTimestamp < lastTimestamp) {
                throw new IllegalStateException(String.format(
                        "系统时钟回拨，拒绝生成 ID。上次时间戳: %d, 当前时间戳: %d",
                        lastTimestamp, currentTimestamp));
            }

            if (currentTimestamp == lastTimestamp) {
                // 同一毫秒内，序列号自增
                sequence = (sequence + 1) & sequenceMask;
                // 序列号耗尽，等待下一毫秒
                if (sequence == 0) {
                    currentTimestamp = waitNextMillis(currentTimestamp);
                }
            } else {
                // 不同毫秒，序列号重置（使用随机初始值避免预测）
                sequence = ThreadLocalRandom.current().nextLong(0, 3);
            }

            lastTimestamp = currentTimestamp;

            // 组装 ID
            return ((currentTimestamp - epoch) << timestampShift)
                    | (workerId << workerIdShift)
                    | sequence;
        }
    }

    /**
    * 生成下一个唯一 ID 的字符串形式
    *
    * @return 十进制字符串表示的 ID
     */
    public String nextIdString() {
        return String.valueOf(nextId());
    }

    /**
    * 解析雪花 ID，返回其组成部件信息
    *
    * @param id 雪花 ID
    * @return 包含时间戳、工作节点 ID、序列号的数组 [timestamp, workerId, sequence]
     */
    public long[] parse(long id) {
        long sequence = id & sequenceMask;
        long workerId = (id >>> workerIdShift) & workerIdMask;
        long timestamp = (id >>> timestampShift) + epoch;

        return new long[]{timestamp, workerId, sequence};
    }

    // ==================== 内部方法 ====================

    /**
    * 获取当前系统时间戳（毫秒）
    *
    * @return 当前时间戳（毫秒）
     */
    private long timestamp() {
        return System.currentTimeMillis();
    }

    /**
    * 自旋等待直到下一毫秒
    *
    * @param lastTimestamp 上次生成 ID 的时间戳
    * @return 下一毫秒的时间戳
     */
    private long waitNextMillis(long lastTimestamp) {
        long currentTimestamp = timestamp();
        while (currentTimestamp <= lastTimestamp) {
            currentTimestamp = timestamp();
        }
        return currentTimestamp;
    }
}
