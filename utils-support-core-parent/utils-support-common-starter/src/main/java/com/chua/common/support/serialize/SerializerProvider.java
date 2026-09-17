package com.chua.common.support.serialize;

import java.io.Serializable;

/**
 * 序列化提供者接口，定义了序列化器的标准获取方法。
 *
 * @author CH
 * @since 1.0.0
*/
public interface SerializerProvider {

    /**
    * 根据类型获取序列化器。
    *
    * @param type 目标类型
    * @return 序列化器
    */
    <T extends Serializable> Serializer<T> getSerializer(Class<T> type);
}
