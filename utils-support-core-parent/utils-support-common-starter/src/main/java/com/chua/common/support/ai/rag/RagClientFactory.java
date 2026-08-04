package com.chua.common.support.ai.rag;

import org.jspecify.annotations.NullUnmarked;

/**
 * RagClient SPI 工厂接口。
 * <p>
 * 实现类通过 {@link com.chua.common.support.spi.ServiceProvider} 注册，
 * 用于创建不同后端的 {@link RagClient} 实现。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public interface RagClientFactory {

    /**
     * 创建 RagClient 实例。
     *
     * @param setting 客户端配置
     * @return RagClient 实例
     */
    RagClient create(RagClientSetting setting);
}
