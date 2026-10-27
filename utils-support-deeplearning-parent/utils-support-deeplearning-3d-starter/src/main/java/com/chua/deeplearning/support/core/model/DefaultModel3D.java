package com.chua.deeplearning.support.core.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 默认 3D 模型实现
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultModel3D implements Model3D {

    /**
     * 名称
    */
    private String name;
    /**
     * 模型三维格式
    */
    private Model3DFormat format;
    /**
     * 数据列表
    */
    private byte[] data;
    /**
     * 纹理数据
    */
    private byte[] texture;

    /**
     * 创建 默认模型3D 实例
     * @param name 名称
     * @param format 模型3d格式化
     * @param byte byte
     * @param data 数据
     * @param format 格式化
     */
    public DefaultModel3D(String name, Model3DFormat format, byte[] data) {
        this.name = name;
        this.format = format;
        this.data = data;
    }

    /**
     * 获取名称
    */
    @Override
    public String getName() {
        return name;
    }

    /**
     * 设置名称
    */
    @Override
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取格式化
    */
    @Override
    public Model3DFormat getFormat() {
        return format;
    }

    /**
     * 设置格式化
    */
    @Override
    public void setFormat(Model3DFormat format) {
        this.format = format;
    }

    /**
     * 获取数据
    */
    @Override
    public InputStream getData() {
        return new ByteArrayInputStream(data != null ? data : new byte[0]);
    }

    /**
     * 设置数据
    */
    @Override
    public void setData(byte[] data) {
        this.data = data;
    }

    /**
     * 获取获取大小
    */
    @Override
    public long getSize() {
        return data != null ? data.length : 0;
    }

    /**
     * 获取Texture
    */
    @Override
    public byte[] getTexture() {
        return texture;
    }

    /**
     * 设置Texture
    */
    @Override
    public void setTexture(byte[] texture) {
        this.texture = texture;
    }

    /**
     * 写入转为
    */
    @Override
    public void writeTo(OutputStream out) throws Exception {
        if (data != null) {
            out.write(data);
        }
    }
}
