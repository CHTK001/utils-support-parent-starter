package com.chua.common.support.lang.code;

import com.chua.common.support.utils.ClassUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 统一返回结果
 * <p>
 * 合并了原 ReturnResultBuilder / ReturnResultsBuilder 的构建能力,
 * 保留静态直接生成、链式构建, 并新增条件分支能力。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
@Accessors(chain = true)
@SuppressWarnings({"ALL", "unchecked"})
public class ReturnResult<T> implements Serializable {

    /**
     * 序列化版本标识
     */
    private static final long serialVersionUID = 1L;

    /**
     * 编码
     */
    private String code;
    /**
     * 数据
     */
    private T data;
    /**
     * 信息
     */
    private String msg;
    /**
     * 临时字段(用于反射填充实体), 不参与序列化
     */
    private transient volatile Map<String, Object> temp;

    /**
     * 创建 ReturnResult 实例
     */
    public ReturnResult() {
    }

    /**
     * 创建 ReturnResult 实例
     * @param code code
     * @param T T
     * @param String String
     * @param data 数据，不允许为 null
     * @param msg 消息，不允许为 null
     */
    public ReturnResult(String code, T data, String msg) {
        this.code = code;
        this.data = data;
        this.msg = msg;
    }

    // ==================== 静态工厂 ====================

    /**
     * 成功
     *
     * @param data 数据
     * @param <T>  类型
     * @return 结果
     */
    public static <T> ReturnResult<T> ok(T data) {
        return ok(data, ReturnCode.SUCCESS.getMsg());
    }

    /**
     * 成功(无数据)
     *
     * @param <T> 类型
     * @return 结果
     */
    public static <T> ReturnResult<T> ok() {
        return ok(null);
    }

    /**
     * 成功
     *
     * @param data 数据
     * @param msg  信息
     * @param <T>  类型
     * @return 结果
     */
    public static <T> ReturnResult<T> ok(T data, String msg) {
        return new ReturnResult<>(ReturnCode.SUCCESS.getCode(), data, msg);
    }

    /**
     * 成功
     *
     * @param <T> 类型
     * @return 结果
     */
    public static <T> ReturnResult<T> success() {
        return ok(null);
    }

    /**
     * 成功（携带数据）
     *
     * @param data 数据
     * @param <T>  类型
     * @return 结果
     */
    public static <T> ReturnResult<T> success(T data) {
        return ok(data);
    }

    /**
     * 根据输入对象自动构造结果
     * <ul>
     *   <li>普通对象: 视为成功数据</li>
     *   <li>Throwable: 根据异常类型自动推断 code</li>
     * </ul>
     *
     * @param data 数据或异常
     * @param <T>  类型
     * @return 结果
     */
    public static <T> ReturnResult<T> of(Object data) {
        if (data instanceof Throwable) {
            return (ReturnResult<T>) error((Throwable) data);
        }
        return ok((T) data);
    }

    /**
     * 失败
     *
     * @param msg 信息
     * @param <T> 类型
     * @return 结果
     */
    public static <T> ReturnResult<T> error(String msg) {
        return error(ReturnCode.SYSTEM_SERVER_BUSINESS_ERROR.getCode(), msg);
    }

    /**
     * 失败
     *
     * @param code 编码
     * @param msg  信息
     * @param <T>  类型
     * @return 结果
     */
    public static <T> ReturnResult<T> error(String code, String msg) {
        return new ReturnResult<>(code, null, msg);
    }

    /**
     * 失败
     *
     * @param resultCode 状态码
     * @param <T>        类型
     * @return 结果
     */
    public static <T> ReturnResult<T> error(ResultCode resultCode) {
        return new ReturnResult<>(resultCode.getCode(), null, resultCode.getMsg());
    }

    /**
     * 失败(编码 + 信息 + 载荷)
     *
     * <p>用于「操作最终失败、但已经产生了可回滚的副作用」这一类结果。典型场景：
     * 创建节点时定义已落库（用户能在列表里看到并修正），随后的启动却失败了。
     * 此时若只返回一段文字，调用方既拿不到 {@code data} 里的 id，也就无从回滚 ——
     * 而「失败后残留半成品」恰恰是最需要调用方自己决定保留还是清理的场景，
     * 不该由服务端替它决定，更不该让它无从下手。</p>
     *
     * <p>与 {@link #error(String, String)} 的区别仅在于 {@code data} 不为 null：
     * {@code success} 仍然是 false，语义没有放宽。它不是「部分成功」，而是
     * 「失败了，并且失败留下了这些东西交给你处理」。</p>
     *
     * @param code 编码
     * @param msg  信息
     * @param data 失败后仍然存在的载荷（如已落库实体的 id）
     * @param <T>  类型
     * @return 结果
     */
    public static <T> ReturnResult<T> error(String code, String msg, T data) {
        return new ReturnResult<>(code, data, msg);
    }

    /**
     * 失败(信息 + 载荷)
     *
     * @param msg  信息
     * @param data 失败后仍然存在的载荷
     * @param <T>  类型
     * @return 结果
     */
    public static <T> ReturnResult<T> error(String msg, T data) {
        return error(ReturnCode.SYSTEM_SERVER_BUSINESS_ERROR.getCode(), msg, data);
    }

    /**
     * 失败(根据异常类型自动推断 code)
     *
     * @param throwable 异常
     * @param <T>       类型
     * @return 结果
     */
    public static <T> ReturnResult<T> error(Throwable throwable) {
        if (null == throwable) {
            return error(ReturnCode.SYSTEM_SERVER_OTHER_ERROR);
        }
        ReturnCode returnCode = ReturnCode.fromThrowable(throwable);
        String message = throwable.getMessage();
        if (null == message || message.isEmpty()) {
            message = returnCode.getMsg();
        }
        return new ReturnResult<>(returnCode.getCode(), null, message);
    }

    /**
     * 失败(根据异常类型自动推断 code, 并附加默认消息)
     *
     * @param throwable 异常
     * @param defaultMsg 默认消息(异常 message 为空时使用)
     * @param <T>       类型
     * @return 结果
     */
    public static <T> ReturnResult<T> error(Throwable throwable, String defaultMsg) {
        if (null == throwable) {
            return error(ReturnCode.SYSTEM_SERVER_OTHER_ERROR);
        }
        ReturnCode returnCode = ReturnCode.fromThrowable(throwable);
        String message = throwable.getMessage();
        if (null == message || message.isEmpty()) {
            message = null == defaultMsg ? returnCode.getMsg() : defaultMsg;
        }
        return new ReturnResult<>(returnCode.getCode(), null, message);
    }

    /**
     * 参数非法
     *
     * @param msg 信息
     * @param <T> 类型
     * @return 结果
     */
    public static <T> ReturnResult<T> illegal(String msg) {
        return error(ReturnCode.REQUEST_PARAM_ERROR.getCode(), msg);
    }

    /**
     * 列表结果
     *
     * @param data 列表
     * @param <T>  类型
     * @return 结果
     */
    public static <T> ReturnResult<List<T>> list(List<T> data) {
        return ok(data);
    }

    // ==================== 链式构建 ====================

    /**
     * 编码
     *
     * @param code 编码
     * @return this
     */
    public ReturnResult<T> code(String code) {
        this.code = code;
        return this;
    }

    /**
     * 信息
     *
     * @param msg 信息
     * @return this
     */
    public ReturnResult<T> msg(String msg) {
        this.msg = msg;
        return this;
    }

    /**
     * 数据
     *
     * @param data 数据
     * @return this
     */
    public ReturnResult<T> data(T data) {
        this.data = data;
        return this;
    }

    /**
     * 添加待反射填充的字段(替代原 ReturnResultBuilder.with)
     *
     * @param field 字段
     * @param value 数据
     * @return this
     */
    public ReturnResult<T> with(String field, Object value) {
        if (null == temp) {
            temp = new HashMap<>();
        }
        temp.put(field, value);
        return this;
    }

    /**
     * 使用临时字段反射填充目标实体的 data
     *
     * @param target 类型
     * @return this
     */
    public ReturnResult<T> build(Class<T> target) {
        if (null == data && null != temp && !temp.isEmpty() && null != target) {
            this.data = analysis(target);
        }
        return this;
    }

    // ==================== 分支能力 ====================

    /**
     * 数据转换(成功时执行, 失败时透传错误)
     *
     * @param function 转换函数
     * @param <R>      目标类型
     * @return 新结果
     */
    public <R> ReturnResult<R> map(Function<T, R> function) {
        if (!isOk()) {
            return ReturnResult.error(this.code, this.msg);
        }
        if (null == data) {
            return ReturnResult.ok(null);
        }
        return ok(function.apply(data));
    }

    /**
     * 链式结果转换(成功时执行, 失败时透传错误)
     *
     * @param function 转换函数
     * @param <R>      目标类型
     * @return 新结果
     */
    public <R> ReturnResult<R> flatMap(Function<T, ReturnResult<R>> function) {
        if (!isOk()) {
            return ReturnResult.error(this.code, this.msg);
        }
        if (null == data) {
            return ReturnResult.ok(null);
        }
        return function.apply(data);
    }

    /**
     * 条件过滤(不满足条件时置为失败)
     *
     * @param predicate 条件
     * @param errorMsg  错误信息
     * @return this
     */
    public ReturnResult<T> filter(Predicate<T> predicate, String errorMsg) {
        if (isOk() && null != data && !predicate.test(data)) {
            this.code = ReturnCode.REQUEST_PARAM_ERROR.getCode();
            this.msg = errorMsg;
            this.data = null;
        }
        return this;
    }

    /**
     * 成功时执行副作用
     *
     * @param consumer 消费函数
     * @return this
     */
    public ReturnResult<T> peek(Consumer<T> consumer) {
        if (isOk() && null != data && null != consumer) {
            consumer.accept(data);
        }
        return this;
    }

    /**
     * 成功时处理
     *
     * @param consumer 消费函数
     * @return this
     */
    public ReturnResult<T> ifOk(Consumer<T> consumer) {
        if (isOk() && null != consumer) {
            consumer.accept(data);
        }
        return this;
    }

    /**
     * 失败时处理
     *
     * @param consumer 消费函数
     * @return this
     */
    public ReturnResult<T> ifError(Consumer<String> consumer) {
        if (!isOk() && null != consumer) {
            consumer.accept(this.msg);
        }
        return this;
    }

    /**
     * 失败时提供默认值并转为成功
     *
     * @param other 默认值
     * @return this
     */
    public ReturnResult<T> orElse(T other) {
        if (!isOk()) {
            this.data = other;
            this.code = ReturnCode.SUCCESS.getCode();
            this.msg = ReturnCode.SUCCESS.getMsg();
        }
        return this;
    }

    /**
     * 失败时提供默认值并转为成功
     *
     * @param supplier 默认值供给
     * @return this
     */
    public ReturnResult<T> orElseGet(Supplier<T> supplier) {
        if (!isOk()) {
            this.data = supplier.get();
            this.code = ReturnCode.SUCCESS.getCode();
            this.msg = ReturnCode.SUCCESS.getMsg();
        }
        return this;
    }

    // ==================== 判断 ====================

    /**
     * 是否成功
     *
     * @return 是否成功
     */
    public boolean isOk() {
        return ReturnCode.SUCCESS.getCode().equals(code) || ReturnCode.OK.getCode().equals(code);
    }

    /**
     * 是否成功(isOk 的语义别名)
     *
     * @return 是否成功
     */
    public boolean isSuccess() {
        return isOk();
    }

    // ==================== 反射填充 ====================

    /**
     * Analysis
     * @param target 目标，不允许为 null
     * @return T 对象
     */
    private T analysis(Class<T> target) {
        T realType = realType(target);
        if (null == realType) {
            return null;
        }
        Class<?> aClass = realType.getClass();
        for (Map.Entry<String, Object> entry : temp.entrySet()) {
            Object value = entry.getValue();
            if (null == value) {
                continue;
            }
            render(aClass, realType, entry.getKey(), value);
        }
        return realType;
    }

    /**
     * Render
     * @param aClass 方法入参 aClass
     * @param realType real类型，不允许为 null
     * @param entryKey 条目键，不允许为 null
     * @param value 值，不允许为 null
     */
    private void render(Class<?> aClass, T realType, String entryKey, Object value) {
        Field field = ClassUtils.findField(aClass, entryKey);
        if (null == field) {
            return;
        }
        ClassUtils.setAccessible(field);
        try {
            ClassUtils.setFieldValue(field, value, realType);
        } catch (Exception ignored) {
        }
    }

    /**
     * RealType
     * @param target 目标，不允许为 null
     * @return T 对象
     */
    private T realType(Class<T> target) {
        Class<?> aClass = this.getClass();
        Type superclass = aClass.getGenericSuperclass();
        if (superclass == Object.class) {
            try {
                return ClassUtils.forObject(target);
            } catch (Exception ignored) {
                return null;
            }
        }
        Type type = null;
        if (superclass instanceof ParameterizedType) {
            type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
        }
        if (!(type instanceof Class<?>)) {
            return null;
        }
        try {
            return (T) ClassUtils.newInstance(((Class<?>) type));
        } catch (Exception e) {
            return null;
        }
    }
}
