package com.chua.common.support.file.resource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
* 基于 {@link URL} 的资源实现。
*
* <p>适用于类路径、JAR、远程 URL 等通过 {@code URL.openStream()} 可读取的资源。
* 资源的最后修改时间通过 {@code URLConnection.getLastModified()} 获取，失败时返回 0。</p>
*
* @author CH
* @since 1.0.0
 */
public class UrlResource implements Resource {

    /**
    * 资源对应的 URL。
     */
    private final URL url;

    /**
    * 使用指定 URL 构造资源。
    *
    * @param url 资源 URL
     */
    public UrlResource(URL url) {
        this.url = url;
    }

    @Override
    /** 打开Stream */
    public InputStream openStream() throws IOException {
        return url.openStream();
    }

    @Override
    /** 获取UrlPath */
    public String getUrlPath() {
        return url.toExternalForm();
    }

    @Override
    /** 获取Url */
    public URL getUrl() {
        return url;
    }

    @Override
    /** LastModified */
    public long lastModified() {
        try {
            return url.openConnection().getLastModified();
        } catch (IOException e) {
            return 0L;
        }
    }
}
