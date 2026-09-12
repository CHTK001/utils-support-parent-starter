package com.chua.mock.support;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.ServiceProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mock 字符串生成门面
 *
 * <p>基于 {@link MockString} SPI 的对外统一入口，按名称获取生成器并生成字符串数据。
 * 支持注入自定义 {@link MockEnvironment}（Mock 环境/上下文）以控制随机源、长度与地区。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * MockStringFactory.generate("name");                 // 随机中文姓名
 * MockStringFactory.generate("phone");                // 随机手机号
 * MockStringFactory.generate("email", 12);            // 指定长度
 * MockStringFactory.generate("random", MockEnvironment.of(8, 16)); // 注入环境
 * }</pre>vironment.of(8, 16)); // 注入环境
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MockStringFactory {

    /**
      * mock字符串 SPI 服务提供者
     */
    private static final ServiceProvider<MockString> PROVIDER = ServiceProvider.of(MockString.class);

    /**
     * 私有构造方法，防止实例化。
     */
    private MockStringFactory() {
    }

    /**
      * 按名称获取 mock字符串 生成器实例。
     *
     * <p>名称匹配大小写不敏感；未注册的名称返回 null，
     * 不会回退到默认实现，便于调用方识别拼写错误。</p>
     *
     * @param name 生成器名称（如 名称、phone、email、uuid 等）
     * @return 生成器实例；名称未注册时返回 空
     */
    @Nullable
    public static MockString getMockString(@Nullable String name) {
        if (!isSupport(name)) {
            return null;
        }
        return PROVIDER.getExtension(name);
    }

    /**
     * 判断指定名称的生成器是否存在。
     *
     * @param name 生成器名称
     * @return true 表示存在
     */
    public static boolean isSupport(@Nullable String name) {
        return null != name && PROVIDER.isSupport(name);
    }

    /**
     * 获取全部已注册的生成器名称集合。
     *
     * @return 名称集合
     */
    @Nonnull
    public static List<String> names() {
        return new ArrayList<>(PROVIDER.getExtensions());
    }

    /**
     * 使用默认 Mock 环境按名称生成字符串数据。
     *
     * @param name 生成器名称
     * @return 生成的字符串数据；生成器不存在时返回 空
     */
    @Nullable
    public static String generate(@Nullable String name) {
        MockString mockString = getMockString(name);
        if (null == mockString) {
            return null;
        }
        return mockString.getString();
    }

    /**
     * 注入 Mock 环境按名称生成字符串数据。
     *
     * @param name        生成器名称
     * @param environment Mock 环境/上下文
     * @return 生成的字符串数据；生成器不存在或环境为 空 时返回 空
     */
    @Nullable
    public static String generate(@Nullable String name, @Nullable MockEnvironment environment) {
        MockString mockString = getMockString(name);
        if (null == mockString || null == environment) {
            return null;
        }
        return mockString.getString(environment);
    }

    /**
     * 按名称生成指定长度的字符串数据（固定长度环境）。
     *
     * @param name   生成器名称
     * @param length 生成长度
     * @return 生成的字符串数据；生成器不存在时返回 空
     */
    @Nullable
    public static String generate(@Nullable String name, int length) {
        return generate(name, MockEnvironment.of(length));
    }

    /**
     * 按名称与关键词生成字符串数据（如按主题生成图片 URL）。
     *
     * @param name    生成器名称
     * @param keyword 生成关键词
     * @return 生成的字符串数据；生成器不存在或关键词为空时返回 空
     */
    @Nullable
    public static String generate(@Nullable String name, @Nullable String keyword) {
        if (null == keyword || keyword.isEmpty()) {
            return null;
        }
        return generate(name, MockEnvironment.ofKeyword(keyword));
    }

    /**
     * 按名称、关键词与长度生成字符串数据（如按主题生成指定尺寸的图片 URL）。
     *
     * @param name    生成器名称
     * @param keyword 生成关键词
     * @param length  生成长度
     * @return 生成的字符串数据；生成器不存在或关键词为空时返回 空
     */
    @Nullable
    public static String generate(@Nullable String name, @Nullable String keyword, int length) {
        if (null == keyword || keyword.isEmpty()) {
            return null;
        }
        return generate(name, MockEnvironment.ofKeyword(keyword, length));
    }

    /**
     * 按名称批量生成字符串数据。
     *
     * @param name  生成器名称
     * @param count 生成数量
     * @return 生成的字符串数据列表；生成器不存在时返回空列表
     */
    @Nonnull
    public static List<String> generateList(@Nullable String name, int count) {
        return generateList(name, count, MockEnvironment.of());
    }

    /**
     * 注入 Mock 环境批量生成字符串数据。
     *
     * @param name        生成器名称
     * @param count       生成数量
     * @param environment Mock 环境/上下文
     * @return 生成的字符串数据列表；生成器不存在或数量小于 1 时返回空列表
     */
    @Nonnull
    public static List<String> generateList(@Nullable String name, int count, @Nullable MockEnvironment environment) {
        MockString mockString = getMockString(name);
        if (null == mockString || count < 1 || null == environment) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(mockString.getString(environment));
        }
        return result;
    }
}