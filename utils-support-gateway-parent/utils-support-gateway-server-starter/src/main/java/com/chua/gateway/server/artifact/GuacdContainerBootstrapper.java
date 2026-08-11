package com.chua.gateway.server.artifact;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Guacd 容器引导器（Docker 路径）。
 *
 * <p>解决 guacd 二进制无 release 的问题：gateway-server 启动时通过 Docker API
 * 创建/启动 guacd 容器（如果尚未运行）。guacd 容器通过 Docker DNS 名 "guacd"
 * 被 gateway 发现，符合现有部署架构。</p>
 *
 * <p>查找顺序：</p>
 * <ol>
 *   <li>容器 "guacd" 已存在且 running → 复用</li>
 *   <li>容器 "guacd" 已存在但 stopped → start</li>
 *   <li>不存在 → image pull + create + start</li>
 *   <li>Docker 不可达 → 返回 null（按"无 guacd"对待，SSH / WS 仍可用）</li>
 * </ol>
 *
 * <p>Docker API 通过 system property {@code docker.api.url} 配置，
 * 默认 {@code http://172.17.0.1:2375}（标准 Docker bridge 网关）。
 * 也可通过环境变量 {@code DOCKER_HOST} 或 {@code DOCKER_API_URL} 覆盖。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class GuacdContainerBootstrapper {

    /**
     * 默认 Docker API base URL（标准 Docker bridge 网关）。
     */
    public static final String DEFAULT_DOCKER_API_URL = "http://172.17.0.1:2375";

    /**
     * guacd 容器名（Docker DNS 名称）。
     */
    public static final String GUACD_CONTAINER_NAME = "guacd";

    /**
     * guacd 官方镜像（Apache Guacamole 官方）。
     */
    public static final String GUACD_IMAGE = "guacamole/guacd:latest";

    /**
     * 容器内 guacd 监听端口。
     */
    public static final int GUACD_PORT = 4822;

    /**
     * 私有构造。
     */
    private GuacdContainerBootstrapper() {
    }

    /**
     * 解析 Docker API base URL（优先级：系统属性 > 环境变量 > 默认值）。
     *
     * @return 解析后的 URL
     */
    public static String resolveDockerApiUrl() {
        String url = System.getProperty("docker.api.url");
        if (url != null && !url.isBlank()) {
            return url;
        }
        url = System.getenv("DOCKER_API_URL");
        if (url != null && !url.isBlank()) {
            return url;
        }
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && dockerHost.startsWith("tcp://")) {
            return dockerHost.replace("tcp://", "http://");
        }
        if (dockerHost != null && dockerHost.startsWith("tcp://")) {
            return dockerHost;
        }
        return DEFAULT_DOCKER_API_URL;
    }

    /**
     * 一键启动 guacd 容器。
     *
     * <p>业务流程：</p>
     * <ol>
     *   <li>GET /containers/json?all=1&filters={"name":["guacd"]} — 查容器</li>
     *   <li>running → 直接返回 OK</li>
     *   <li>existed stopped → POST /containers/{id}/start</li>
     *   <li>not found → POST /containers/create?name=guacd（image=guacamole/guacd:latest）
     *       + POST /containers/{id}/start</li>
     * </ol>
     *
     * @return true 表示 guacd 容器在跑（无论新启还是复用），false 表示失败
     */
    public static boolean ensureGuacdContainer() {
        String apiUrl = resolveDockerApiUrl();
        log.info("[guacd-container-bootstrapper] Docker API URL: {}", apiUrl);

        // 1. 健康检查
        if (!pingDocker(apiUrl)) {
            log.warn("[guacd-container-bootstrapper] Docker 不可达: {} (跳过 guacd 容器启动)", apiUrl);
            return false;
        }

        // 2. 查找已有 guacd 容器
        String existingId = findContainerByName(apiUrl, GUACD_CONTAINER_NAME);
        if (existingId != null) {
            log.info("[guacd-container-bootstrapper] ✓ 找到现有 guacd 容器: id={}", existingId);
            if (isContainerRunning(apiUrl, existingId)) {
                log.info("[guacd-container-bootstrapper] ✓ guacd 容器已在运行");
                return true;
            }
            // 存在但 stopped → 启动
            log.info("[guacd-container-bootstrapper] guacd 容器 stopped，尝试启动...");
            if (startContainer(apiUrl, existingId)) {
                log.info("[guacd-container-bootstrapper] ✓ guacd 容器已启动");
                return true;
            }
            log.warn("[guacd-container-bootstrapper] 启动现有 guacd 容器失败");
            return false;
        }

        // 3. 不存在 → 创建并启动
        log.info("[guacd-container-bootstrapper] guacd 容器不存在，尝试创建...");
        String newId = createContainer(apiUrl, GUACD_CONTAINER_NAME);
        if (newId == null) {
            log.warn("[guacd-container-bootstrapper] 创建 guacd 容器失败");
            return false;
        }
        log.info("[guacd-container-bootstrapper] ✓ 容器已创建: id={}", newId);
        if (startContainer(apiUrl, newId)) {
            log.info("[guacd-container-bootstrapper] ✓ guacd 容器已启动 (image={})", GUACD_IMAGE);
            return true;
        }
        log.warn("[guacd-container-bootstrapper] 启动新 guacd 容器失败");
        return false;
    }

    /**
     * 健康检查 Docker daemon。
     */
    private static boolean pingDocker(String apiUrl) {
        try {
            HttpURLConnection conn = openConn(apiUrl + "/_ping", "GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception ex) {
            log.debug("[guacd-container-bootstrapper] ping {} fail: {}", apiUrl, ex.getMessage());
            return false;
        }
    }

    /**
     * 按名查找容器（包含已停止的）。
     */
    private static String findContainerByName(String apiUrl, String name) {
        try {
            String url = apiUrl + "/containers/json?all=1&filters="
                    + java.net.URLEncoder.encode("{\"name\":[\"" + name + "\"]}", "UTF-8");
            HttpURLConnection conn = openConn(url, "GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }
            String body = readResponse(conn);
            conn.disconnect();
            // 简单解析：找第一个含 "Id" 的对象
            int idx = body.indexOf("\"Id\"");
            if (idx < 0) {
                return null;
            }
            int start = body.indexOf("\"", idx + 4) + 1;
            int end = body.indexOf("\"", start);
            String id = body.substring(start, end);
            // Docker 返回 64 字符 hex
            return id.length() == 64 ? id : null;
        } catch (Exception ex) {
            log.warn("[guacd-container-bootstrapper] 查找容器失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 检查容器是否 running。
     */
    private static boolean isContainerRunning(String apiUrl, String containerId) {
        try {
            HttpURLConnection conn = openConn(apiUrl + "/containers/" + containerId + "/json", "GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return false;
            }
            String body = readResponse(conn);
            conn.disconnect();
            return body.contains("\"Running\":true");
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 启动容器。
     */
    private static boolean startContainer(String apiUrl, String containerId) {
        try {
            HttpURLConnection conn = openConn(apiUrl + "/containers/" + containerId + "/start", "POST");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            // 204 = started, 304 = already running
            return code == 204 || code == 304;
        } catch (Exception ex) {
            log.warn("[guacd-container-bootstrapper] 启动容器失败: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 创建容器（暴露 4822 端口，host network 兼容）。
     */
    private static String createContainer(String apiUrl, String name) {
        try {
            String body = "{\n" +
                    "  \"Image\": \"" + GUACD_IMAGE + "\",\n" +
                    "  \"ExposedPorts\": {\"" + GUACD_PORT + "/tcp\": {}},\n" +
                    "  \"HostConfig\": {\n" +
                    "    \"PortBindings\": {\"" + GUACD_PORT + "/tcp\": [{\"HostIp\": \"0.0.0.0\", \"HostPort\": \"" + (14822) + "\"}]},\n" +
                    "    \"RestartPolicy\": {\"Name\": \"unless-stopped\"}\n" +
                    "  }\n" +
                    "}";
            HttpURLConnection conn = openConn(apiUrl + "/containers/create?name=" + name, "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 201) {
                String err = readError(conn);
                log.warn("[guacd-container-bootstrapper] create 失败: code={} err={}", code, err);
                conn.disconnect();
                return null;
            }
            String resp = readResponse(conn);
            conn.disconnect();
            int idx = resp.indexOf("\"Id\"");
            if (idx < 0) {
                return null;
            }
            int start = resp.indexOf("\"", idx + 4) + 1;
            int end = resp.indexOf("\"", start);
            return resp.substring(start, end);
        } catch (Exception ex) {
            log.warn("[guacd-container-bootstrapper] 创建容器异常: {}", ex.getMessage());
            return null;
        }
    }

    private static HttpURLConnection openConn(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        return conn;
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        try (java.io.InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readError(HttpURLConnection conn) {
        try (java.io.InputStream is = conn.getErrorStream()) {
            if (is == null) {
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }
}
