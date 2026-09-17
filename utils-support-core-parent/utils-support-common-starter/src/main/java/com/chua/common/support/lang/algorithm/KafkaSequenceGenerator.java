package com.chua.common.support.lang.algorithm;

import java.util.ArrayList;
import java.util.List;

/**
* Kafka 自增序列 ID 生成器，一种改进的雪花算法变体，适用于高吞吐消息场景。
*
* <p>64 位 Long 型 ID 的位分配如下（可自定义）：</p>
* <pre>
*   1 bit sign   |  40 bits timestamp  |  2 bits dataCenterId  |  8 bits workerId  |  13 bits sequence
*   (始终为 0)   |  (相对自定义纪元)    |  (数据中心 ID)       |  (工作节点 ID)   |  (自增序号)
* </pre>
*
* <p>特性：</p>
* <ul>
*   <li>相比标准雪花算法，增加了数据中心 ID 维度，适合跨机房部署</li>
*   <li>序列号位更多（13 位），单节点每毫秒可生成 8192 个 ID</li>
*   <li>支持批量预生成，提升高并发下的吞吐性能</li>
*   <li>支持 ID 组成解析，便于故障排查</li>
* </ul>
*
* <p>使用方法：</p>
* <pre>{@code
* // 创建生成器（数据中心 1，工作节点 1）
* KafkaSequenceGenerator generator = new KafkaSequenceGenerator(1, 1);
*
* // 生成单个 ID
* long id = generator.nextId();
*
* // 批量生成 ID（减少锁竞争）
* List<Long> ids = generator.nextIds(100);
* }</pre>
*
* @author CH
* @since 1.0.0
 */
public class KafkaSequenceGenerator {

    // ==================== 默认位分配 ====================

    /** 时间戳占用位数 */
    private static final long DEFAULT_TIMESTAMP_BITS = 40L;

    /** 数据中心 ID 占用位数 */
    private static final long DEFAULT_DATA_CENTER_BITS = 2L;

    /** 工作节点 ID 占用位数 */
    private static final long DEFAULT_WORKER_ID_BITS = 8L;

    /** 序列号占用位数 */
    private static final long DEFAULT_SEQUENCE_BITS = 13L;

    // ==================== 移位偏移量 ====================

    /** 序列号移位偏移 */
    private static final long SEQUENCE_SHIFT = 0L;

    /** 工作节点 ID 移位偏移 */
    private final long workerIdShift;

    /** 数据中心 ID 移位偏移 */
    private final long dataCenterIdShift;

    /** 时间戳移位偏移 */
    private final long timestampShift;

    // ==================== 掩码 ====================

    /** 序列号掩码 */
    private final long sequenceMask;

    /** 工作节点 ID 掩码 */
    private final long workerIdMask;

    /** 数据中心 ID 掩码 */
    private final long dataCenterIdMask;

    /** 时间戳最大值 */
    private final long maxTimestamp;

    // ==================== 默认值 ====================

    /**
    * 默认纪元起始时间（2020-01-01 00:00:00 UTC，单位毫秒）
    */
    private static final long DEFAULT_EPOCH = 1577836800000L;

    /** 默认数据中心 ID */
    private static final long DEFAULT_DATA_CENTER_ID = 0L;

    /** 默认工作节点 ID */
    private static final long DEFAULT_WORKER_ID = 0L;

    // ==================== 实例状态 ====================

    /** 纪元起始时间（毫秒） */
    private final long epoch;

    /** 数据中心 ID */
    private final long dataCenterId;

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
    * 使用默认配置创建 Kafka 自增序列 ID 生成器
    *
    * <p>数据中心 ID 为 0，工作节点 ID 为 0。</p>
    */
    public KafkaSequenceGenerator() {
        this(DEFAULT_DATA_CENTER_ID, DEFAULT_WORKER_ID, DEFAULT_EPOCH,
                DEFAULT_DATA_CENTER_BITS, DEFAULT_WORKER_ID_BITS,
                DEFAULT_TIMESTAMP_BITS, DEFAULT_SEQUENCE_BITS);
    }

    /**
    * 使用指定的数据中心和工作节点 ID 创建生成器
    *
    * @param dataCenterId 数据中心 ID
    * @param workerId     工作节点 ID
    * @throws IllegalArgumentException 当参数超出范围时
    */
    public KafkaSequenceGenerator(long dataCenterId, long workerId) {
        this(dataCenterId, workerId, DEFAULT_EPOCH,
                DEFAULT_DATA_CENTER_BITS, DEFAULT_WORKER_ID_BITS,
                DEFAULT_TIMESTAMP_BITS, DEFAULT_SEQUENCE_BITS);
    }

    /**
    * 使用完全自定义的位分配创建生成器
    *
    * @param dataCenterId     数据中心 ID
    * @param workerId         工作节点 ID
    * @param epoch            纪元起始时间（毫秒）
    * @param dataCenterBits   数据中心 ID 占用位数
    * @param workerIdBits     工作节点 ID 占用位数
    * @param timestampBits    时间戳占用位数
    * @param sequenceBits     序列号占用位数
    * @throws IllegalArgumentException 当参数超出范围时
    */
    public KafkaSequenceGenerator(long dataCenterId, long workerId, long epoch,
                                  long dataCenterBits, long workerIdBits,
                                  long timestampBits, long sequenceBits) {
        // 计算掩码
        this.dataCenterIdMask = ~(-1L << dataCenterBits);
        this.workerIdMask = ~(-1L << workerIdBits);
        this.sequenceMask = ~(-1L << sequenceBits);
        this.maxTimestamp = ~(-1L << timestampBits);

        // 校验取值范围
        if (dataCenterId < 0 || dataCenterId > dataCenterIdMask) {
            throw new IllegalArgumentException(String.format(
                    "数据中心 ID 必须介于 0 ~ %d 之间，当前值: %d", dataCenterIdMask, dataCenterId));
        }
        if (workerId < 0 || workerId > workerIdMask) {
            throw new IllegalArgumentException(String.format(
                    "工作节点 ID 必须介于 0 ~ %d 之间，当前值: %d", workerIdMask, workerId));
        }

        // 计算移位偏移
        this.workerIdShift = sequenceBits;
        this.dataCenterIdShift = workerIdBits + sequenceBits;
        this.timestampShift = dataCenterBits + workerIdBits + sequenceBits;

        this.dataCenterId = dataCenterId;
        this.workerId = workerId;
        this.epoch = epoch;
    }

    // ==================== ID 生成方法 ====================

    /**
    * 生成下一个唯一 ID
    *
    * @return 64 位 Long 型唯一 ID
    * @throws IllegalStateException 如果系统时钟回拨或时间戳溢出
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
                sequence = (sequence + 1) & sequenceMask;
                if (sequence == 0) {
                    currentTimestamp = waitNextMillis(currentTimestamp);
                }
            } else {
                sequence = 0L;
            }

            lastTimestamp = currentTimestamp;
            return buildId(currentTimestamp);
        }
    }

    /**
    * 批量生成唯一 ID，减少锁竞争开销
    *
    * @param count 生成数量
    * @return 唯一 ID 列表
    * @throws IllegalArgumentException 如果 count 小于 1
    * @throws IllegalStateException    如果系统时钟回拨
    */
    public List<Long> nextIds(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("生成数量必须大于 0，当前值: " + count);
        }

        synchronized (lock) {
            List<Long> ids = new ArrayList<>(count);
            long currentTimestamp = timestamp();

            if (currentTimestamp < lastTimestamp) {
                throw new IllegalStateException(String.format(
                        "系统时钟回拨，拒绝生成 ID。上次时间戳: %d, 当前时间戳: %d",
                        lastTimestamp, currentTimestamp));
            }

            for (int i = 0; i < count; i++) {
                if (currentTimestamp == lastTimestamp) {
                    sequence = (sequence + 1) & sequenceMask;
                    if (sequence == 0) {
                        currentTimestamp = waitNextMillis(currentTimestamp);
                    }
                } else {
                    sequence = 0L;
                }

                lastTimestamp = currentTimestamp;
                ids.add(buildId(currentTimestamp));
            }

            return ids;
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
    * 解析序列 ID，返回其组成部件信息
    *
    * @param id 序列 ID
    * @return 包含时间戳、数据中心 ID、工作节点 ID、序列号的数组 [timestamp, dataCenterId, workerId, sequence]
    */
    public long[] parse(long id) {
        long sequence = id & sequenceMask;
        long workerId = (id >>> workerIdShift) & workerIdMask;
        long dataCenterId = (id >>> dataCenterIdShift) & dataCenterIdMask;
        long timestamp = (id >>> timestampShift) + epoch;

        return new long[]{timestamp, dataCenterId, workerId, sequence};
    }

    // ==================== 内部方法 ====================

    /**
    * 组装 64 位 ID
    *
    * @param currentTimestamp 当前时间戳
    * @return 组装后的 64 位 ID
    */
    private long buildId(long currentTimestamp) {
        long relativeTimestamp = currentTimestamp - epoch;

        if (relativeTimestamp > maxTimestamp) {
            throw new IllegalStateException(String.format(
                    "时间戳超出最大值，无法生成 ID。相对时间戳: %d, 最大值: %d",
                    relativeTimestamp, maxTimestamp));
        }

        return (relativeTimestamp << timestampShift)
                | (dataCenterId << dataCenterIdShift)
                | (workerId << workerIdShift)
                | sequence;
    }

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
