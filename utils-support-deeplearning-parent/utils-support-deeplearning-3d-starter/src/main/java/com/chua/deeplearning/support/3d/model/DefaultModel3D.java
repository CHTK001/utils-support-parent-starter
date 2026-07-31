package com.chua.deeplearning.support._3d.model;

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

    private String name;
    private Model3DFormat format;
    private byte[] data;
    private byte[] texture;

    public DefaultModel3D(String name, Model3DFormat format, byte[] data) {
        this.name = name;
        this.format = format;
        this.data = data;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public Model3DFormat getFormat() {
        return format;
    }

    @Override
    public void setFormat(Model3DFormat format) {
        this.format = format;
    }

    @Override
    public InputStream getData() {
        return new ByteArrayInputStream(data != null ? data : new byte[0]);
    }

    @Override
    public void setData(byte[] data) {
        this.data = data;
    }

    @Override
    public long getSize() {
        return data != null ? data.length : 0;
    }

    @Override
    public byte[] getTexture() {
        return texture;
    }

    @Override
    public void setTexture(byte[] texture) {
        this.texture = texture;
    }

    @Override
    public void writeTo(OutputStream out) throws Exception {
        if (data != null) {
            out.write(data);
        }
    }
}