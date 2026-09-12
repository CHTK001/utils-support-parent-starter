package com.chua.common.support.file.resource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

/**
* 基于 {@link File} 的文件系统资源实现。
*
* <p>资源通过 {@link FileInputStream} 读取，URL 由 {@code File.toURI().toURL()} 派生，
* 最后修改时间取自 {@link File#lastModified()}。</p>
*
* @author CH
* @since 1.0.0
 */
public class FileSystemResource implements Resource {

    /**
    * 资源对应的文件。
     */
    private final File file;

    /**
    * 使用指定文件构造资源。
    *
    * @param file 文件对象
     */
    public FileSystemResource(File file) {
        this.file = file;
    }

    @Override
    /** 打开Stream */
    public InputStream openStream() throws IOException {
        return new FileInputStream(file);
    }

    @Override
    /** 获取UrlPath */
    public String getUrlPath() {
        return file.getAbsolutePath();
    }

    @Override
    /** 获取Url */
    public URL getUrl() {
        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("无法将文件转换为 URL", e);
        }
    }

    @Override
    /** LastModified */
    public long lastModified() {
        return file.lastModified();
    }
}
