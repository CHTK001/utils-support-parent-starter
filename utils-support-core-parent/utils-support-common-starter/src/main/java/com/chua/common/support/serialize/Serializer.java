package com.chua.common.support.serialize;

import java.io.Serializable;

/**
* 序列化接口，定义了对象序列化和反序列化的标准方法。
*
* @author CH
* @since 1.0.0
 */
public interface Serializer<T extends Serializable> {

    /**
    * 将对象序列化为字节数组。
    *
    * @param object 待序列化的对象
    * @return 字节数组
     */
    byte[] serialize(T object);

    /**
    * 将字节数组反序列化为指定类型的对象。
    *
    * @param bytes 字节数组
    * @return 反序列化后的对象
     */
    T deserialize(byte[] bytes);
}
