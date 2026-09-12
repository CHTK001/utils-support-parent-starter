package com.chua.common.support.shmqueue;

import java.util.ServiceLoader;

/**
   * shm队列 抽象基类 / 用户侧 API 入口。
 *
 * <p>本类定义 SPSC 共享内存环形队列的统一 Java 接口，不依赖任何具体原生实现。
   * 具体后端（如 utils-support-shm-queue-starter 提供的 libshmqueue.so 绑定）通过
 * {@link ShmQueueProvider} SPI 机制加载，运行时通过 {@link ServiceLoader} 自动发现。</p>
 *
 * <h2>典型用法</h2>
 * <pre>
 *   try (ShmQueue q = ShmQueue.create("/demo", 1024, 256, Mode.HYBRID)) {
 *       q.send(1, "hello".getBytes());
 *       Message m = q.recv();
 *       System.out.println(m.type() + " " + new String(m.bytes()));
 *   }
 * </pre>
 *
 * <h2>添加新实现</h2>
 * <ol>
 *   <li>实现 {@link ShmQueueProvider}，返回一个 {@link ShmQueue} 子类实例</li>
 *   <li>在 {@code resources/META-INF/services/com.chua.common.support.shmqueue.ShmQueueProvider}
 *       中注册实现类全限定名</li>
 * </ol>
 *
 * @since 4.0.0.42
 * @author CH
 */
public abstract class ShmQueue implements AutoCloseable {

    /**
     * 等待模式
      * @author CH
     * @since 4.0.0
     */
    public enum Mode {
        /**
         * 纯自旋：低延迟高 CPU
         */
        SPIN(0),
        /**
          * 纯阻塞：eventfd/win事件，低 CPU
         */
        BLOCK(1),
        /**
         * 混合：先自旋再阻塞
         */
        HYBRID(2);

        /**
         * 数值编码（与 C 端保持兼容；某些实现可能直接复用）
         */
        public final int code;

        Mode(int code) {
            this.code = code;
        }
    }

    /**
     * 消息体
     * @param type 类型
     * @param bytes bytes
     * @return 消息的结果
     */
    public record Message(int type, byte[] bytes) {
        /**
          * 防御性拷贝：空 视为空数组
         */
        public Message {
            bytes = bytes == null ? new byte[0] : bytes;
        }

        @Override
        /** 转为字符串 */
        public String toString() {
            return "Message{type=" + type + ", len=" + bytes.length + "}";
        }
    }

    /**
     * C 端错误码（与 SHMQ_ERR_* 宏对齐，跨实现统一）
     */
    public static final int ERR_OK              = 0;

    /**
     * 无效参数错误码
     */
    public static final int ERR_INVALID_ARG     = -1;

    /**
     * 内存不足错误码
     */
    public static final int ERR_NOMEM           = -2;

    /**
     * 共享内存打开失败错误码
     */
    public static final int ERR_OPEN_SHM        = -3;

    /**
     * 截断失败错误码
     */
    public static final int ERR_TRUNCATE        = -4;

    /**
     * 内存映射失败错误码
     */
    public static final int ERR_MMAP            = -5;

    /**
     * 头魔数错误错误码
     */
    public static final int ERR_HEADER_MAGIC    = -6;

    /**
     * 头版本错误错误码
     */
    public static final int ERR_HEADER_VERSION  = -7;

    /**
     * 队列已满错误码
     */
    public static final int ERR_QUEUE_FULL      = -8;

    /**
     * 数据过大错误码
     */
    public static final int ERR_DATA_TOO_LARGE  = -9;

    /**
     * 写入文件描述符错误码
     */
    public static final int ERR_WRITE_FD        = -10;

    /**
     * 读取文件描述符错误码
     */
    public static final int ERR_READ_FD         = -11;

    /**
     * 超时错误码
     */
    public static final int ERR_TIMEOUT         = -12;

    /**
     * 不支持操作错误码
     */
    public static final int ERR_NOT_SUPPORTED   = -13;

    /**
     * 队列已销毁错误码
     */
    public static final int ERR_DESTROYED       = -14;

    /**
     * 创建或附加到一个共享内存队列。
     *
     * <p>遍历 {@link ServiceLoader} 找到第一个可用的 {@link ShmQueueProvider}，
     * 委托其创建实例。</p>
     *
     * @param name      共享内存对象名
     * @param capacity  槽位数
     * @param slotSize  每槽字节数
     * @param mode      等待模式
     * @return ShmQueue 实例
     * @throws ShmQueueException     创建失败
     * @throws IllegalStateException  未找到任何 提供者
     */
    public static ShmQueue create(String name, int capacity, int slotSize, Mode mode) {
        ShmQueueProvider provider = firstProvider();
        return provider.create(name, capacity, slotSize, mode);
    }

    /**
     * 仅附加到已存在的共享内存队列。
     *
     * @param name 共享内存对象名
     * @return ShmQueue 实例
     * @throws ShmQueueException     attach 失败
     * @throws IllegalStateException  未找到任何 提供者
     */
    public static ShmQueue attach(String name) {
        ShmQueueProvider provider = firstProvider();
        return provider.attach(name);
    }

    /**
      * 查找第一个可用的 提供者 实现。
     *
     * @return 第一个 提供者
     * @throws IllegalStateException 未找到任何 提供者
     */
    private static ShmQueueProvider firstProvider() {
        for (ShmQueueProvider p : ServiceLoader.load(ShmQueueProvider.class)) {
            return p;
        }
        throw new IllegalStateException(
                "未找到任何 ShmQueueProvider SPI 实现。请在 classpath 中加入提供实现的具体模块（如 "
                        + "utils-support-native-shm-queue）");
    }

    /**
     * 发送一条消息。
     *
     * @param msgType 消息类型
     * @param data    数据（可为 空 表示空消息）
     * @throws ShmQueueException 队列满/数据过大等错误
     */
    public abstract void send(int msgType, byte[] data);

    /**
     * 接收一条消息（无限等待）。
     *
     * @return 消息对象
     * @throws ShmQueueException 接收错误
     */
    public abstract Message recv();

    /**
     * 接收一条消息，带超时。
     *
     * @param timeoutNanos 超时纳秒；{@code <= 0} 表示无限等待
     * @return 消息对象
     * @throws ShmQueueException 超时或其他接收错误
     */
    public abstract Message recvTimeout(long timeoutNanos);

    /**
      * 设置混合模式的自旋时间（纳秒）。Spin/BLOCK 模式下此调用无意义。
     * @param spinNs Spinns
     */
    public abstract void setSpinNanos(long spinNs);

    /**
     * 关闭并释放底层资源。重复调用安全。
     */
    @Override
    public abstract void close();
}
