package com.chua.common.support.base.serialize;


/**
 * 序列化接口。
 *
 * <p>定义了对象的序列化与反序列化标准，所有序列化实现必须实现此接口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Serialization {

    /**
     * 获取序列化名称。
     *
     * @return 序列化名称
     */
    String name();

    /**
     * 将对象序列化为字节数组。
     *
     * @param obj 要序列化的对象
     * @return 序列化后的字节数组
     * @throws Exception 序列化异常
     */
    byte[] serialize(Object obj) throws Exception;

    /**
     * 从字节数组反序列化为指定类型的对象。
     *
     * @param data 序列化后的字节数组
     * @param type 目标类型
     * @param <T>  目标类型泛型
     * @return 反序列化后的对象
     * @throws Exception 反序列化异常
     */
    <T> T deserialize(byte[] data, Class<T> type) throws Exception;
}