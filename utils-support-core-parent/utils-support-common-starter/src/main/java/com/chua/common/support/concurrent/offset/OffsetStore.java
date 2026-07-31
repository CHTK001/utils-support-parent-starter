package com.chua.common.support.concurrent.offset;

/**
 * offset 存储 SPI。
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface OffsetStore extends AutoCloseable {

    Offset getOffset(String subscriberId);

    Offset removeOffset(String subscriberId);

    void truncate();

    void start();

    @Override
    void close();
}