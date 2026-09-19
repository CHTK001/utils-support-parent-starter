package com.chua.common.support.concurrent.offset;


/**
 * offset 存储 SPI。
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface OffsetStore extends AutoCloseable {

    /**
     * 获取偏移量。
     *
     * @param subscriberId subscriberID，不允许为 null
     * @return 偏移量 对象
     */
    Offset getOffset(String subscriberId);

    /**
     * 移除偏移量。
     *
     * @param subscriberId subscriberID，不允许为 null
     * @return 偏移量 对象
     */
    Offset removeOffset(String subscriberId);

    /**
     * truncate。
     */
    void truncate();

    /**
     * 启动。
     */
    void start();

    @Override
    void close();
}