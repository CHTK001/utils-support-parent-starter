package com.chua.common.support.concurrent.offset;

import com.chua.common.support.concurrent.offset.provider.FileOffsetStore;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullUnmarked;

/**
 * offset 门面，支持链式调用、provider 切换和重试操作。
 *
 * <pre>{@code
 * OffsetFlow flow = OffsetFlow.create()
 *     .persistent(false)
 *     .basePath("/tmp/myoffsets")
 *     .provider("file")
 *     .start();
 *
 * long offset = flow.advance("sub-001");
 * long current = flow.current("sub-001");
 * flow.reset("sub-001", 0);
 * flow.remove("sub-001");
 * flow.truncate();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@SuppressWarnings("NullAway")
@NullUnmarked
public final class OffsetFlow implements AutoCloseable {

    /**
     * 重试次数
     */
    private static final int RETRY_COUNT = 3;

    /**
     * 重试间隔（毫秒）
     */
    private static final long RETRY_DELAY_MS = 100L;

    /**
     * 偏移量存储
     */
    private OffsetStore store;

    /**
     * 配置
     */
    private OffsetConfig config;

    private OffsetFlow(OffsetConfig config) {
        this.config = config;
    }

    /**
     * 创建 OffsetFlow 实例，使用默认配置。
     *
     * @return OffsetFlow 实例
     */
    public static OffsetFlow create() {
        return new OffsetFlow(OffsetConfig.createDefault());
    }

    /**
     * 创建 OffsetFlow 实例，使用指定配置。
     *
     * @param config offset 配置
     * @return OffsetFlow 实例
     */
    public static OffsetFlow of(OffsetConfig config) {
        return new OffsetFlow(config);
    }

    /**
     * 设置文件存储根目录（链式）。
     *
     * @param basePath 根目录字符串
     * @return this
     */
    public OffsetFlow basePath(String basePath) {
        config.setBasePath(java.nio.file.Paths.get(basePath));
        return this;
    }

    /**
     * 设置是否持久化（链式）。
     *
     * @param persistent 持久化标志
     * @return this
     */
    public OffsetFlow persistent(boolean persistent) {
        config.setPersistent(persistent);
        return this;
    }

    /**
     * 设置 provider 名称（链式）。
     *
     * @param provider provider 名
     * @return this
     */
    public OffsetFlow provider(String provider) {
        config.setProvider(provider);
        return this;
    }

    /**
     * 获取提供者名称。
     *
     * @return provider 名称
     */
    public String getProvider() {
        return config.getProvider();
    }

    /**
     * 启动偏移量存储。
     *
     * @return this
     */
    public OffsetFlow start() {
        if (store == null) {
            store = findProvider(config.getProvider());
            if (store == null) {
                store = new FileOffsetStore(config);
            }
            store.start();
        }
        return this;
    }

    /**
     * 原子推进 offset（+1），返回新值。
     *
     * @param subscriberId 订阅器 ID
     * @return 新 offset 值
     */
    public long advance(String subscriberId) {
        Offset offset = getStore().getOffset(subscriberId);
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                return offset.incrementAndGet();
            } catch (Exception e) {
                log.warn("advance offset 重试: subscriberId={}, attempt={}", subscriberId, i + 1, e);
                sleep();
            }
        }
        throw new IllegalStateException("advance offset failed: " + subscriberId);
    }

    /**
     * 获取当前 offset 值。
     *
     * @param subscriberId 订阅器 ID
     * @return 当前 offset 值
     */
    public long current(String subscriberId) {
        Offset offset = getStore().getOffset(subscriberId);
        return offset.value();
    }

    /**
     * 重置 offset。
     *
     * @param subscriberId 订阅器 ID
     * @param newValue     新值
     */
    public void reset(String subscriberId, long newValue) {
        Offset offset = getStore().getOffset(subscriberId);
        offset.reset(newValue);
    }

    /**
     * 删除指定订阅器的 offset。
     *
     * @param subscriberId 订阅器 ID
     */
    public void remove(String subscriberId) {
        getStore().removeOffset(subscriberId);
    }

    /**
     * 清空所有 offset 记录。
     */
    public void truncate() {
        getStore().truncate();
    }

    /**
     * 获取或初始化存储。
     */
    private OffsetStore getStore() {
        if (store == null) {
            start();
        }
        return store;
    }

    private OffsetStore findProvider(String providerName) {
        return ServiceProvider.of(OffsetStore.class).getNewExtension(providerName, config);
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (store != null) {
            store.close();
            store = null;
        }
    }
}