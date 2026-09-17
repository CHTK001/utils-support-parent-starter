package com.chua.common.support.lang.config;

import lombok.Builder;
import lombok.Data;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
* 配置保存和加载的设置类。
* 用于定义配置文件存储路径、字符集、连接超时等参数。
* @author CH
* @since 4.0.0.42
 */@Data
@Builder
public class ConfigSaveLoadSetting {

    /**
    * 配置文件的根目录路径，默认为用户主目录下的 .config 文件夹。
    * 格式：{user.home}/.config
    */
    @Builder.Default
    /** 根级路径 */
    private String rootPath = System.getProperty("user.home", ".") + "/.config";

    /**
    * 文件读写使用的字符集，默认为 UTF-8。
    */
    @Builder.Default
    /**
    * 字符集
    */
    private Charset charset = StandardCharsets.UTF_8;

    /**
    * 远程服务的端点地址（例如 API URL）。
    */
    private String endpoint;

    /**
    * 基础路径，通常用于拼接完整的请求路径。
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
    * 连接超时时间（毫秒），默认值为 5000ms (5秒)。
    */
    @Builder.Default
    /** Connect超时毫秒 */
    private long connectTimeoutMillis = 5000;

    /**
    * 读取超时时间（毫秒），默认值为 5000ms (5秒)。
    */
    @Builder.Default
    /** Read超时毫秒 */
    private long readTimeoutMillis = 5000;

    /**
    * 内容类型（Content-Type），默认值为 application/octet-stream。
    */
    @Builder.Default
    /** 内容类型 */
    private String contentType = "application/octet-stream";

    /**
    * 获取内容类型字符串。
    *
    * @return 内容类型
    */
    public String getContentType() { return contentType; }
}
