package com.chua.common.support.config.loader;

import lombok.Builder;
import lombok.Data;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NullUnmarked;

/**
 * 配置保存/加载设置。
 *
 * <p>封装配置持久化操作的通用设置，包括存储根路径、字符编码、
 * 连接超时、认证信息等。支持构建者模式创建实例。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
@Data
@Builder
public class ConfigSaveLoadSetting {

    /**
     * 配置存储根路径。
     *
     * <p>本地文件存储时的根目录，默认为 ${user.home}/.config。</p>
     */
    @Builder.Default
    private String rootPath = System.getProperty("user.home", ".") + "/.config";

    /**
     * 字符编码。
     *
     * <p>读写配置文件时使用的字符集，默认为 UTF-8。</p>
     */
    @Builder.Default
    /**
     * 字符集
     */
    private Charset charset = StandardCharsets.UTF_8;

    /**
     * 远程配置中心端点地址。
     */
    private String endpoint;

    /**
     * 基础路径前缀。
     */
    private String basePath;

    /**
     * 认证用户名。
     */
    private String username;

    /**
     * 认证密码。
     */
    private String password;

    /**
     * 连接超时时间（毫秒）。
     *
     * <p>与远程配置中心建立连接的最大等待时间，默认 5000ms。</p>
     */
    @Builder.Default
    private long connectTimeoutMillis = 5000;

    /**
     * 读取超时时间（毫秒）。
     *
     * <p>等待远程配置中心返回数据的最大时间，默认 5000ms。</p>
     */
    @Builder.Default
    private long readTimeoutMillis = 5000;

    /**
     * 内容类型。
     *
     * <p>配置数据传输时的 Content-Type 头，默认 "application/octet-stream"。</p>
     */
    @Builder.Default
    private String contentType = "application/octet-stream";

    public String getContentType() { return contentType; }
}
