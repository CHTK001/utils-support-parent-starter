package com.chua.common.support.mock;

import com.chua.common.support.utils.StringUtils;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
* Mock 环境/上下文
*
* <p>作为 {@link MockString} 的注入式上下文，将随机数源、生成长度区间、地区、
* 字符集等参数统一传递给字符串生成器，保证生成的 Mock 数据可复现、可控。</p>
*
* <p>通过 {@link #random()}、{@link #nextInt(int)}、{@link #length()} 等
* 便捷方法向生成器暴露统一的随机与长度取值能力；如需可复现结果，
* 可通过 {@link #setSeed(long)} 固定随机种子。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Getter
@Setter
public class MockEnvironment {

    /**
    * 默认生成长度下限
     */
    public static final int DEFAULT_MIN_LENGTH = 1;
    /**
    * 默认生成长度上限
     */
    public static final int DEFAULT_MAX_LENGTH = 64;
    /**
    * 默认字符集
     */
    public static final String DEFAULT_CHARSET = "UTF-8";

    /**
    * 随机数源（为 null 时懒加载 {@link ThreadLocalRandom#current()}）
     */
    private Random random;
    /**
    * 生成长度下限
     */
    private int minLength = DEFAULT_MIN_LENGTH;
    /**
    * 生成长度上限
     */
    private int maxLength = DEFAULT_MAX_LENGTH;
    /**
    * 生成数据对应的地区
     */
    private Locale locale = Locale.CHINA;
    /**
    * 生成数据使用的字符集
     */
    private String charset = DEFAULT_CHARSET;
    /**
    * 生成关键词（如按主题生成图片时使用的搜索词）
     */
    private String keyword;

    /**
    * 创建默认 Mock 环境。
    *
    * @return 默认 Mock 环境
     */
    @Nonnull
    public static MockEnvironment of() {
        return new MockEnvironment();
    }

    /**
    * 创建指定长度的 Mock 环境（长度下限与上限均为指定值）。
    *
    * @param length 生成长度
    * @return Mock 环境
     */
    @Nonnull
    public static MockEnvironment of(int length) {
        return of(length, length);
    }

    /**
    * 创建长度区间 Mock 环境。
    *
    * @param minLength 长度下限
    * @param maxLength 长度上限
    * @return Mock 环境
     */
    @Nonnull
    public static MockEnvironment of(int minLength, int maxLength) {
        MockEnvironment environment = new MockEnvironment();
        environment.setMinLength(minLength);
        environment.setMaxLength(maxLength);
        return environment;
    }

    /**
    * 创建固定随机种子的 Mock 环境（支持可复现结果）。
    *
    * @param seed 随机种子
    * @return Mock 环境
     */
    @Nonnull
    public static MockEnvironment of(long seed) {
        MockEnvironment environment = new MockEnvironment();
        environment.setSeed(seed);
        return environment;
    }

    /**
    * 创建指定地区的 Mock 环境。
    *
    * @param locale 地区
    * @return Mock 环境
     */
    @Nonnull
    public static MockEnvironment of(Locale locale) {
        MockEnvironment environment = new MockEnvironment();
        environment.setLocale(locale);
        return environment;
    }

    /**
    * 创建带关键词的 Mock 环境（默认长度）。
    *
    * @param keyword 生成关键词
    * @return Mock 环境
     */
    @Nonnull
    public static MockEnvironment ofKeyword(@Nonnull String keyword) {
        MockEnvironment environment = new MockEnvironment();
        environment.setKeyword(keyword);
        return environment;
    }

    /**
    * 创建带关键词与指定长度的 Mock 环境。
    *
    * @param keyword 生成关键词
    * @param length  生成长度
    * @return Mock 环境
     */
    @Nonnull
    public static MockEnvironment ofKeyword(@Nonnull String keyword, int length) {
        MockEnvironment environment = of(length);
        environment.setKeyword(keyword);
        return environment;
    }

    /**
    * 设置随机种子，创建可复现的随机数源。
    *
    * @param seed 随机种子
     */
    public void setSeed(long seed) {
        this.random = new Random(seed);
    }

    /**
    * 获取随机数源；未设置时懒加载线程本地随机数。
    *
    * @return 随机数源
     */
    @Nonnull
    public Random random() {
        Random current = this.random;
        if (null == current) {
            current = ThreadLocalRandom.current();
        }
        return current;
    }

    /**
    * 生成 [0, bound) 之间的随机整数。
    *
    * @param bound 上界（不包含）
    * @return 随机整数
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            return 0;
        }
        return random().nextInt(bound);
    }

    /**
    * 生成 [origin, bound) 之间的随机整数。
    *
    * @param origin 下界（包含）
    * @param bound  上界（不包含）
    * @return 随机整数
     */
    public int nextInt(int origin, int bound) {
        if (bound <= origin) {
            return origin;
        }
        return origin + random().nextInt(bound - origin);
    }

    /**
    * 生成 [0, bound) 之间的随机长整数。
    *
    * @param bound 上界（不包含）
    * @return 随机长整数
     */
    public long nextLong(long bound) {
        if (bound <= 0) {
            return 0;
        }
        return random().nextLong(bound);
    }

    /**
    * 生成 [origin, bound) 之间的随机长整数。
    *
    * @param origin 下界（包含）
    * @param bound  上界（不包含）
    * @return 随机长整数
     */
    public long nextLong(long origin, long bound) {
        if (bound <= origin) {
            return origin;
        }
        return origin + random().nextLong(bound - origin);
    }

    /**
    * 获取当前实际生成长度。
    *
    * <p>当长度区间有效时返回区间内的随机长度，否则返回上下限矫正后的固定值。</p>
    *
    * @return 实际生成长度
     */
    public int length() {
        int min = Math.min(minLength, maxLength);
        int max = Math.max(minLength, maxLength);
        if (max <= min) {
            return Math.max(min, 1);
        }
        return nextInt(min, max + 1);
    }

    /**
    * 从给定数组中随机选取一个元素。
    *
    * @param values 元素数组
    * @param <T>    元素类型
    * @return 随机元素；数组为 null 或为空时返回 null
     */
    @Nonnull
    public <T> T randomOf(T[] values) {
        if (null == values || values.length == 0) {
            throw new IllegalArgumentException("随机选取的数据源不能为空");
        }
        return values[nextInt(values.length)];
    }

    /**
    * 判断当前环境是否配置了预设的随机数源（固定种子）。
    *
    * @return true 表示配置了固定随机数源
     */
    public boolean hasSeededRandom() {
        return null != this.random;
    }

    /**
    * 校验并返回合法的字符集名称。
    *
    * @return 字符集名称，为空时返回默认字符集
     */
    @Nonnull
    public String charset() {
        return StringUtils.isBlank(charset) ? DEFAULT_CHARSET : charset;
    }
}