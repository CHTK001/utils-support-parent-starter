package com.chua.common.support.file.resource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 基于 {@link Path} 的文件路径资源实现。
 *
 * <p>适用于以纯路径字符串描述的文件系统资源。输入流通过 {@link Files#newInputStream(Path)} 打开，
 * URL 由 {@code Path.toUri().toURL()} 派生，最后修改时间取自 {@link Files#getLastModifiedTime}。</p>
 *
 * @author CH
 * @since 1.0.0
 */
public class PathResource implements Resource {

    /**
     * 资源对应的路径。
     */
    private final Path path;

    /**
     * 使用路径字符串构造资源。
     *
     * @param path 路径字符串
     */
    public PathResource(String path) {
        this(Paths.get(path));
    }

    /**
     * 使用 {@link Path} 构造资源。
     *
     * @param path 路径对象
     */
    public PathResource(Path path) {
        this.path = path;
    }

    @Override
    /** 打开Stream */
    public InputStream openStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    /** 获取UrlPath */
    public String getUrlPath() {
        return path.toString();
    }

    @Override
    /** 获取Url */
    public URL getUrl() {
        try {
            return path.toUri().toURL();
        } catch (IOException e) {
            throw new IllegalStateException("无法将路径转换为 URL", e);
        }
    }

    @Override
    /** LastModified */
    public long lastModified() {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
