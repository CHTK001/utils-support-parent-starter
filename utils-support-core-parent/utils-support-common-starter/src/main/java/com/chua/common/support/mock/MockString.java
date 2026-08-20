package com.chua.common.support.mock;

import javax.annotation.Nonnull;

/**
 * Mock 字符串生成接口
 *
 * <p>通过 SPI 机制提供各类字符串 Mock 数据生成契约，采用「注入式」设计：
 * 调用方将 {@link MockEnvironment}（Mock 环境/上下文）注入到生成方法中，
 * 生成器依据上下文中的随机数源、长度区间、地区等参数产出对应的字符串数据。</p>
 *
 * <p>实现类通过 {@code @Spi("名称")} + {@code @AutoSpi} 注册，例如：</p>
 * <pre>{@code
 * @Spi("name")
 * @AutoSpi(value = "com.chua.common.support.mock.MockString")
 * public class NameMockString implements MockString {
 *     @Override
 *     public String getString(MockEnvironment environment) {
 *         return ...;
 *     }
 * }
 * }</pre>
 *
 * <p>调用方可直接使用 SPI 注入方式获取实现：</p>
 * <pre>{@code
 * @Spi("name")
 * private MockString nameMock;
 *
 * String name = nameMock.getString(MockEnvironment.of());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MockString {

    /**
     * 根据注入的 Mock 环境/上下文生成字符串数据。
     *
     * @param environment Mock 环境/上下文，提供随机数源、长度区间、地区等参数
     * @return 生成的字符串数据
     */
    @Nonnull
    String getString(@Nonnull MockEnvironment environment);

    /**
     * 使用默认 Mock 环境/上下文生成字符串数据。
     *
     * @return 生成的字符串数据
     */
    @Nonnull
    default String getString() {
        return getString(MockEnvironment.of());
    }
}
