package com.chua.common.support.concurrent.offset;


/**
 * offset 持久化操作。
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface Offset extends AutoCloseable {

    String subscriberId();

    long value();

    long incrementAndGet();

    void reset(long newValue);

    String offsetPath();

    void flush();

    @Override
    void close();
}