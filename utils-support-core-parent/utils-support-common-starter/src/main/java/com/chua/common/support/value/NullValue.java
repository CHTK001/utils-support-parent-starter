package com.chua.common.support.value;


/**
 * 空值实现（空对象模式）。
 * <p>
 * 当值为 空 时，{@link Value#of(Object)} 返回此单例，避免空指针异常。
 * 所有方法都针对 空 语义进行了安全处理。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class NullValue implements Value<Object> {

    /**
     * 全局单例实例
    */
    static final NullValue INSTANCE = new NullValue();

    /**
     * 私有构造函数，防止外部实例化
    */
    private NullValue() {
    }

    /**
     * 始终返回 空。
     *
     * @return null
     */
    @Override
    public Object getValue() {
        return null;
    }

    /**
     * 始终返回 空。
     *
     * @return null
     */
    @Override
    public Throwable getThrowable() {
        return null;
    }

    /**
     * 始终返回 true，表示值为空。
     *
     * @return true
     */
    @Override
    public boolean isNull() {
        return true;
    }

    /**
     * 判断指定值是否为 空。
     *
     * @param value 指定值
     * @return true 表示指定值为 空
     */
    @Override
    public boolean is(Object value) {
        return value == null;
    }
}
