package com.chua.common.support.file.resource;

import com.chua.common.support.utils.FileUtils;
import com.chua.common.support.utils.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.chua.common.support.constant.CommonConstant.FILE_PROTOCOL;

/**
 * 资源抽象接口，统一描述类路径、文件系统、URL 等不同来源的可读取资源。
 *
 * <p>该接口是 {@code file.resource} 包中所有资源查找器的产出物，屏蔽底层来源差异，
 * 提供统一的流读取、URL 访问、文件名/后缀获取等能力。</p>
 *
 * <p>通过静态工厂方法 {@link #create(String)}、{@link #create(URL)}、{@link #create(File)}
 * 根据入参类型选择合适的实现：</p>
 * <ul>
 *   <li>字符串形如 URL（含协议头）—— {@link UrlResource}</li>
 *   <li>普通文件路径字符串 —— {@link PathResource}</li>
 *   <li>{@link URL} —— {@link UrlResource}</li>
 *   <li>{@link File} —— {@link FileSystemResource}</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
public interface Resource {

    /**
     * 根据路径字符串创建资源。
     *
     * <p>当字符串包含协议头（如 {@code jar:}、{@code file:}、{@code http:}）时按 URL 解析，
     * 否则按文件系统路径解析。</p>
     *
     * @param path 路径或 URL 字符串
     * @return 资源实例
     */
    static Resource create(String path) {
        int protocolIndex = path.indexOf(':');
        if (protocolIndex > 0 && protocolIndex < 6) {
            try {
                return new UrlResource(new URL(path));
            } catch (MalformedURLException ignored) {
                // 协议头无法解析为 URL，退化为路径处理
            }
        }
        return new PathResource(path);
    }

    /**
     * 根据 {@link URL} 创建资源。
     *
     * @param url URL 对象
     * @return 资源实例
     */
    static Resource create(URL url) {
        return new UrlResource(url);
    }

    /**
     * 根据 {@link File} 创建资源。
     *
     * @param file 文件对象
     * @return 资源实例
     */
    static Resource create(File file) {
        return new FileSystemResource(file);
    }

    /**
     * 打开资源的输入流。
     *
     * @return 资源输入流
     * @throws IOException 打开流失败时抛出
     */
    InputStream openStream() throws IOException;

    /**
     * 获取资源的输入流，默认委托给 {@link #openStream()}。
     *
     * @return 资源输入流
     * @throws IOException 打开流失败时抛出
     */
    default InputStream getInputStream() throws IOException {
        return openStream();
    }

    /**
     * 获取资源的 URL 路径字符串形式。
     *
     * @return URL 路径字符串
     */
    String getUrlPath();

    /**
     * 获取资源的 {@link URL}。
     *
     * @return 资源 URL
     */
    URL getUrl();

    /**
     * 获取资源的最后修改时间戳。
     *
     * @return 最后修改时间（毫秒），无法获取时返回 0
     */
    long lastModified();

    /**
     * 获取资源名称（不含路径的文件名部分）。
     *
     * @return 资源名称
     */
    default String getName() {
        return FileUtils.getName(getUrlPath());
    }

    /**
     * 将资源内容写入输出流。
     *
     * @param out 目标输出流
     */
    default void writeTo(OutputStream out) {
        try {
            IoUtils.copy(openStream(), out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 判断资源是否为文件系统文件。
     *
     * @return 如果是 file 协议返回 true，否则返回 false
     */
    default boolean isFile() {
        URL url = getUrl();
        return null != url && FILE_PROTOCOL.equals(url.getProtocol());
    }

    /**
     * 获取资源对应的 {@link File} 对象。
     *
     * @return 文件对象
     */
    default File getFile() {
        return new File(getUrlPath());
    }

    /**
     * 获取资源对应的 {@link Path} 对象。
     *
     * @return 路径对象
     */
    default Path getPath() {
        return Paths.get(getUrlPath());
    }

    /**
     * 获取资源的文件后缀（不含点号）。
     *
     * @return 文件后缀，无后缀时返回空串
     */
    default String getSuffix() {
        return FileUtils.getExtension(getName());
    }
}
