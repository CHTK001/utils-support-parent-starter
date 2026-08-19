package com.chua.gateway.server.config;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 服务端静态配置（基于 {@link Properties}，纯 JDK 加载）。
 *
 * <p>加载优先级：
 *   1. {@code classpath:gateway.properties}（默认配置）
 *   2. {@code ${user.home}/.utils-support-gateway/gateway.properties}（用户自定义，可覆盖默认）
 * </p>
 *
 * <p>这些值在服务启动时一次性读取，运行期间不变（CH 规则 11：SPI 在启动期间不变）。</p>
 *
 * @since 4.0.0.42
 */
@Slf4j
public final class GatewayProperties {

    /**
     * 默认 HTTP 服务端口
     */
    public static final int DEFAULT_HTTP_PORT = 8080;

    /**
     * 默认 guacd 子进程端口
     */
    public static final int DEFAULT_GUACD_PORT = 4822;

    /**
     * 默认 WebSocket 桥接端口
     */
    public static final int DEFAULT_WS_PORT = 8182;

    /**
     * 默认 artifact 下载目录占位符
     */
    public static final String DEFAULT_ARTIFACT_DIR = "${user.home}/.utils-support-gateway/cache";

    /**
     * 默认本地回退覆盖目录占位符
     */
    public static final String DEFAULT_LOCAL_OVERRIDE_DIR = "${user.home}/.utils-support-gateway/local-override";

    /**
     * 默认 sqlite 数据库 URL 占位符
     */
    public static final String DEFAULT_DB_URL = "jdbc:sqlite:${user.home}/.utils-support-gateway/gateway.db";

    /**
     * 全局加载的配置
     */
    private static final Properties PROPS = new Properties();

    /**
     * 配置缓存目录路径
     */
    private static final Path USER_PROPERTIES_PATH = Paths.get(
            System.getProperty("user.home", ""),
            ".utils-support-gateway", "gateway.properties");

    /**
     * user.home 系统属性名
     */
    private static final String USER_HOME = "user.home";

    static {
        loadClasspathDefault();
        loadUserOverride();
    }

    /**
     * 私有构造，禁止实例化。
     */
    private GatewayProperties() {
    }

    /**
     * 加载 classpath:gateway.properties 默认配置。
     */
    private static void loadClasspathDefault() {
        try (InputStream is = GatewayProperties.class.getResourceAsStream("/gateway.properties")) {
            if (is != null) {
                PROPS.load(is);
                log.info("[gateway-server] 已加载默认配置 gateway.properties");
            }
        } catch (IOException e) {
            log.warn("[gateway-server] 加载默认配置失败，使用代码内默认值: {}", e.getMessage());
        }
    }

    /**
     * 加载用户自定义配置（可覆盖默认）。
     */
    private static void loadUserOverride() {
        if (!Files.exists(USER_PROPERTIES_PATH)) {
            return;
        }
        try (InputStream is = Files.newInputStream(USER_PROPERTIES_PATH)) {
            PROPS.load(is);
            log.info("[gateway-server] 已加载用户自定义配置: {}", USER_PROPERTIES_PATH);
        } catch (IOException e) {
            log.warn("[gateway-server] 加载用户自定义配置失败: {}", e.getMessage());
        }
    }

    /**
     * 读取字符串配置；缺失返回 {@code defaultValue}（占位符 {@code ${user.home}} 被替换）。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 解析后的字符串值
     */
    public static String getOrDefault(String key, String defaultValue) {
        String value = PROPS.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.replace("${user.home}", System.getProperty(USER_HOME, ""));
    }

    /**
     * 读取整数配置；缺失或解析失败返回 {@code defaultValue}。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 解析后的整数值
     */
    public static int getIntOrDefault(String key, int defaultValue) {
        String value = PROPS.getProperty(key);
        try {
            return value == null ? defaultValue : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * HTTP 服务端口。
     *
     * <p>优先级：System property {@code gateway.http.port} &gt; properties 文件 &gt; 默认 8080。</p>
     *
     * @return 端口号
     */
    public static int httpPort() {
        String sysPort = System.getProperty("gateway.http.port");
        if (sysPort != null && !sysPort.trim().isEmpty()) {
            try {
                return Integer.parseInt(sysPort.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return getIntOrDefault("gateway.http.port", DEFAULT_HTTP_PORT);
    }

    /**
     * artifact 默认下载目录路径。
     *
     * @return 路径字符串（已展开 {@code ${user.home}}）
     */
    public static String artifactDir() {
        return getOrDefault("gateway.artifact.dir", DEFAULT_ARTIFACT_DIR);
    }

    /**
     * 本地回退覆盖目录路径（用户手动复制 jar/native 用）。
     *
     * @return 路径字符串（已展开 {@code ${user.home}}）
     */
    public static String localOverrideDir() {
        return getOrDefault("gateway.artifact.local-override.dir", DEFAULT_LOCAL_OVERRIDE_DIR);
    }

    /**
     * guacd 子进程主机（默认 127.0.0.1，可配置为远程 guacd 地址）
     *
     * @return 主机地址
     */
    public static String guacdHost() {
        return PROPS.getProperty("gateway.guacd.host", "127.0.0.1");
    }

    /**
     * guacd 子进程端口。
     *
     * @return 端口号
     */
    public static int guacdPort() {
        return getIntOrDefault("gateway.guacd.port", DEFAULT_GUACD_PORT);
    }

    /**
     * WebSocket 桥接服务端口（独立于 HTTP server）。
     *
     * @return 端口号
     */
    public static int wsPort() {
        return getIntOrDefault("gateway.ws.port", DEFAULT_WS_PORT);
    }

    /**
     * 默认 Docker API 地址（TCP）占位符
     */
    public static final String DEFAULT_DOCKER_HOST = "http://127.0.0.1:2375";

    /**
     * Docker API 地址（用于通过远程 Docker 拉起 guacd 容器，一键式方案）。
     * 可通过 {@code gateway.guacd.docker-host} 或环境变量 {@code DOCKER_HOST} 覆盖。
     *
     * @return Docker API 地址
     */
    public static String dockerHost() {
        String env = System.getenv("DOCKER_HOST");
        if (env != null && !env.trim().isEmpty()) {
            if (env.contains("://")) {
                return env;
            }
            return "http://" + env;
        }
        String sys = System.getProperty("gateway.guacd.docker-host");
        if (sys != null && !sys.trim().isEmpty()) {
            return sys;
        }
        return getOrDefault("gateway.guacd.docker-host", DEFAULT_DOCKER_HOST);
    }

    /**
     * 通过 Docker 拉起 guacd 时使用的容器镜像。
     *
     * @return 镜像名（默认 guacamole/guacd:latest）
     */
    public static String guacdDockerImage() {
        return getOrDefault("gateway.guacd.docker-image", "guacamole/guacd:latest");
    }

    /**
     * 嵌入式数据库 JDBC URL（默认 sqlite）。
     *
     * @return JDBC URL 字符串（已展开 {@code ${user.home}}）
     */
    public static String dbUrl() {
        return getOrDefault("gateway.db.url", DEFAULT_DB_URL);
    }
}
