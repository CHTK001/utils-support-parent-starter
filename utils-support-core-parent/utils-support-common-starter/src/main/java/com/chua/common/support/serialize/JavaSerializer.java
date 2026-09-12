package com.chua.common.support.serialize;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.io.*;
import java.io.Serializable;

/**
   * Java原生序列化实现，基于对象输入流/对象输出流。
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("java")
@SpiDefault
public class JavaSerializer<T extends Serializable> implements Serializer<T> {
    private static final long serialVersionUID = 1L; // 串行版本uid

    @Override
    /** 序列化 */
    public byte[] serialize(T object) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(object);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 反序列化
     *
     * @param bytes bytes
     * @return deserialize的结果
     */
    public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
