package com.chua.deeplearning.support.core.model;

import java.io.InputStream;
import java.io.OutputStream;

/**
* 3D 模型
*
* @author CH
* @since 4.0.0.42
 */
public interface Model3D {

    /**
    * 获取模型名称
    *
    * @return 模型名称
     */
    String getName();

    /**
    * 设置模型名称
    *
    * @param name 模型名称
     */
    void setName(String name);

    /**
    * 获取格式
    *
    * @return 格式
     */
    Model3DFormat getFormat();

    /**
    * 设置格式
    *
    * @param format 格式
     */
    void setFormat(Model3DFormat format);

    /**
    * 获取模型数据输入流
    *
    * @return 输入流
     */
    InputStream getData();

    /**
    * 设置模型数据
    *
    * @param data 模型字节数据
     */
    void setData(byte[] data);

    /**
    * 获取模型大小（字节）
    *
    * @return 大小
     */
    long getSize();

    /**
    * 获取纹理（如果有）
    *
    * @return 纹理图片数据，可能为空
     */
    byte[] getTexture();

    /**
    * 设置纹理
    *
    * @param texture 纹理图片数据
     */
    void setTexture(byte[] texture);

    /**
    * 写入输出流
    *
    * @param out 输出流
    * @throws Exception 写入异常
     */
    void writeTo(OutputStream out) throws Exception;
}