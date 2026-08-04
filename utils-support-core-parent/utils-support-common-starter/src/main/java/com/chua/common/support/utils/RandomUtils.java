package com.chua.common.support.utils;

import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.NullUnmarked;

/**
 * 随机数工具类
 *
 * <p>提供基本数据类型随机数生成与枚举随机选取能力，
 * 基于 {@link ThreadLocalRandom} 实现，线程安全且性能优于 {@link java.util.Random}。</p>
 *
 * <h2>能力矩阵</h2>
 * <table border="1">
 *   <tr><th>能力</th><th>方法</th><th>说明</th></tr>
 *   <tr><td>区间随机整数</td><td>{@link #randomInt(int, int)}</td><td>返回 [origin, bound) 之间的随机整数</td></tr>
 *   <tr><td>随机枚举值</td><td>{@link #randomEnum(Class)}</td><td>从枚举类中随机选取一个常量</td></tr>
 * </table>
 *
 * <h2>线程安全</h2>
 * <p>所有方法均为静态方法，依赖 {@link ThreadLocalRandom#current()}，可在多线程中并发调用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public final class RandomUtils {

    /**
     * 私有构造方法，防止实例化
     */
    private RandomUtils() {
    }

    /**
     * 生成指定区间内的随机整数。
     *
     * <p>当 {@code bound <= origin} 时直接返回 {@code origin}，避免 {@link IllegalArgumentException}。</p>
     *
     * @param origin 区间下界（包含）
     * @param bound  区间上界（不包含）
     * @return [origin, bound) 之间的随机整数
     */
    public static int randomInt(int origin, int bound) {
        if (bound <= origin) {
            return origin;
        }
        return ThreadLocalRandom.current().nextInt(origin, bound);
    }

    /**
     * 从指定枚举类中随机选取一个常量。
     *
     * @param enumClass 枚举类对象
     * @param <E>       枚举类型
     * @return 随机选中的枚举常量；当枚举类为 null 或无常量时返回 null
     */
    public static <E extends Enum<E>> E randomEnum(Class<E> enumClass) {
        if (enumClass == null) {
            return null;
        }
        E[] constants = enumClass.getEnumConstants();
        if (constants == null || constants.length == 0) {
            return null;
        }
        return constants[ThreadLocalRandom.current().nextInt(constants.length)];
    }
}
