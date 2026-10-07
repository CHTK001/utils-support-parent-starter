package com.chua.common.support.content;

import com.chua.common.support.converter.Converter;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 通用内容上下文。
 *
 * <p>各处的「上下文」读值写值收敛到同一份契约：{@code Rule} 的
 * 求值上下文、对象装配、决策输入等都按本接口暴露变量，
 * 调用方不再需要记住每种上下文各自的取值方法。
 * 类型转换统一走 {@link Converter}，本接口的 {@code xxxOf} 系列
 * 只是按变量名的薄封装。</p>
 *
 * <p>实现要求：{@link #get(String)} 读不到时返回 null（不抛异常，
 * 条件求值场景判空比接异常好写）；存在性判断用 {@link #contains(String)}，
 * 区分「变量不存在」与「变量值为 null」。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface ObjectContent {

    /**
     * 读取变量。
     *
     * @param name 变量名或路径
     * @return 变量值；不存在或值为 null 时返回 null
     */
    Object get(String name);

    /**
     * 判断变量是否存在。
     *
     * @param name 变量名或路径
     * @return 存在返回 true
     */
    boolean contains(String name);

    /**
     * 读取必需变量。
     *
     * @param name 变量名
     * @return 变量值，值本身允许为 null
     * @throws NoSuchElementException 变量不存在时
     */
    default Object require(String name) {
        Objects.requireNonNull(name, "name 不能为 null");
        if (!contains(name)) {
            throw new NoSuchElementException("必需变量不存在: " + name);
        }
        return get(name);
    }

    /**
     * 读取变量并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 变量不存在或值为 null 时返回的默认值
     * @param <T>          返回值类型
     * @return 变量值或默认值
     */
    <T> T getOrDefault(String name, T defaultValue);

    /**
     * 写入变量。
     *
     * @param name  变量名
     * @param value 变量值，允许为 null
     * @param <T>   实现类型
     * @return 自身，便于链式调用
     */
    <T extends ObjectContent> T set(String name, Object value);

    /**
     * 读字符串。
     *
     * @param name 变量名
     * @return 字符串；不存在或转不动时返回 null
     */
    default String stringOf(String name) {
        return Converter.stringOf(get(name));
    }

    /**
     * 读字符串并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 默认值
     * @return 字符串或默认值
     */
    default String stringOf(String name, String defaultValue) {
        return Converter.stringOf(get(name), defaultValue);
    }

    /**
     * 读整数。
     *
     * @param name 变量名
     * @return 整数；不存在或转不动时返回 null
     */
    default Integer intOf(String name) {
        return Converter.intOf(get(name));
    }

    /**
     * 读整数并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 默认值
     * @return 整数或默认值
     */
    default int intOf(String name, int defaultValue) {
        return Converter.intOf(get(name), defaultValue);
    }

    /**
     * 读长整数。
     *
     * @param name 变量名
     * @return 长整数；不存在或转不动时返回 null
     */
    default Long longOf(String name) {
        return Converter.longOf(get(name));
    }

    /**
     * 读长整数并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 默认值
     * @return 长整数或默认值
     */
    default long longOf(String name, long defaultValue) {
        return Converter.longOf(get(name), defaultValue);
    }

    /**
     * 读双精度浮点。
     *
     * @param name 变量名
     * @return 双精度浮点；不存在或转不动时返回 null
     */
    default Double doubleOf(String name) {
        return Converter.doubleOf(get(name));
    }

    /**
     * 读双精度浮点并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 默认值
     * @return 双精度浮点或默认值
     */
    default double doubleOf(String name, double defaultValue) {
        return Converter.doubleOf(get(name), defaultValue);
    }

    /**
     * 读单精度浮点。
     *
     * @param name 变量名
     * @return 单精度浮点；不存在或转不动时返回 null
     */
    default Float floatOf(String name) {
        return Converter.floatOf(get(name));
    }

    /**
     * 读单精度浮点并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 默认值
     * @return 单精度浮点或默认值
     */
    default float floatOf(String name, float defaultValue) {
        return Converter.floatOf(get(name), defaultValue);
    }

    /**
     * 读短整数。
     *
     * @param name 变量名
     * @return 短整数；不存在或转不动时返回 null
     */
    default Short shortOf(String name) {
        return Converter.shortOf(get(name));
    }

    /**
     * 读短整数并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 默认值
     * @return 短整数或默认值
     */
    default short shortOf(String name, short defaultValue) {
        return Converter.shortOf(get(name), defaultValue);
    }

    /**
     * 读字节。
     *
     * @param name 变量名
     * @return 字节；不存在或转不动时返回 null
     */
    default Byte byteOf(String name) {
        return Converter.byteOf(get(name));
    }

    /**
     * 读字节并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 默认值
     * @return 字节或默认值
     */
    default byte byteOf(String name, byte defaultValue) {
        return Converter.byteOf(get(name), defaultValue);
    }

    /**
     * 读布尔。
     *
     * @param name 变量名
     * @return 布尔；不存在或转不动时返回 null
     */
    default Boolean booleanOf(String name) {
        return Converter.booleanOf(get(name));
    }

    /**
     * 读布尔并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 默认值
     * @return 布尔或默认值
     */
    default boolean booleanOf(String name, boolean defaultValue) {
        return Converter.booleanOf(get(name), defaultValue);
    }

    /**
     * 读字符。
     *
     * @param name 变量名
     * @return 字符；不存在或转不动时返回 null
     */
    default Character charOf(String name) {
        return Converter.charOf(get(name));
    }

    /**
     * 读字符并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 默认值
     * @return 字符或默认值
     */
    default char charOf(String name, char defaultValue) {
        return Converter.charOf(get(name), defaultValue);
    }

    /**
     * 读数值（阈值比较用）。
     *
     * @param name 变量名
     * @return 数值；不存在或转不动时返回 null
     */
    default Double numberOf(String name) {
        return Converter.doubleOf(get(name));
    }

    /**
     * 读数值并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 取不到数值时的返回值
     * @return 数值或默认值
     */
    default double numberOf(String name, double defaultValue) {
        return Converter.doubleOf(get(name), defaultValue);
    }
}
