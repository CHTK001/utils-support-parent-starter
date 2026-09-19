package com.chua.common.support.concurrent.offset;


/**
 * offset 持久化操作。
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface Offset extends AutoCloseable {

    /**
     * subscriberID。
     *
     * @return 结果字符串
     */
    String subscriberId();

    /**
     * 值。
     *
     * @return 结果数值
     */
    long value();

    /**
     * incrementAnd获取。
     *
     * @return 结果数值
     */
    long incrementAndGet();

    /**
     * reset。
     *
     * @param newValue 新建值，不允许为 null
     */
    void reset(long newValue);

    /**
     * 偏移量路径。
     *
     * @return 结果字符串
     */
    String offsetPath();

    /**
     * 刷写。
     */
    void flush();

    @Override
    void close();
}