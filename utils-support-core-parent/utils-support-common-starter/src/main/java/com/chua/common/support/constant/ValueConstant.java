package com.chua.common.support.constant;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Properties;

/**
 * 常量数据
 * @author CH
 * @since 4.0.0.42
 */
public final class ValueConstant {
    private ValueConstant() {}
    /**
     * 空字符串常量。
     */
    String SYMBOL_EMPTY_STRING = "";

    /**
     * 空字节数组
     */
    Byte[] SYMBOL_EMPTY_OBJECT_BYTE_ARRAY = new Byte[0];
    /**
     * 空浮点数数组
     */
    Float[] SYMBOL_EMPTY_OBJECT_FLOAT_ARRAY = new Float[0];

    /**
     * 空字节数组
     */
    byte[] SYMBOL_EMPTY_BYTE_ARRAY = new byte[0];
    /**
     * 空 String 数组常量。
     */
    String[] SYMBOL_EMPTY_ARRAY = new String[0];
    /**
     * 空 String 数组（引用自 SYMBOL_EMPTY_ARRAY）。
     */
    String[] SYMBOL_EMPTY_STRING_ARRAY = SYMBOL_EMPTY_ARRAY;
    /**
     * 空 Object 数组常量。
     */
    Object[] SYMBOL_EMPTY_OBJECT_ARRAY = new Object[0];
    /**
     * 空 Method 数组常量。
     */
    Method[] SYMBOL_EMPTY_METHOD_ARRAY = new Method[0];
    /**
     * 空 Class 数组常量。
     */
    Class<?>[] SYMBOL_EMPTY_CLASS_ARRAY = new Class<?>[0];
    /**
     * 空 Properties 常量。
     */
    Properties EMPTY_PROPERTIES = new Properties();
    /**
     * 空 ByteBuffer 常量。
     */
    ByteBuffer EMPTY_BYTE_BUFFER = ByteBuffer.allocate(0);

    /**
     * 空 Class 数组常量（用于接口数组初始值）。
     */
    Class<?>[] SYMBOL_EMPTY_CLASS = new Class<?>[0];
}
