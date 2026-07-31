package com.chua.utils.support.appimage;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * AppImage 健康检查器
 * <p>
 * 通过 HTTP 请求或进程状态检查 AppImage 是否正常运行
 *
 * @author CH
 */
@Slf4j
public class AppImageHealthChecker {

    /*
     * 默认超时时间
     */
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    /*
     * 健康检查成功关键字
     */
    private static final String HEALTH_CHECK_SUCCESS = "UP";

    /**
     * 执行健康检查
     *
     * @param config AppImage 运行时配置
     * @return true 健康检查通过
     */
    public boolean checkHealth(AppImageRuntimeConfig config) {
        if (config == null) {
            log.warn("健康检查配置为空");
            return false;
        }

        log.debug("开始健康检查: {}", config.getAppImageId());

        if (config.getAppImageId() != null) {
            AppImageInstance instance = AppImageManager.getInstance().getInstance(config.getAppImageId());
            if (instance != null && instance.getPid() > 0) {
                if (!isProcessAlive(instance.getPid())) {
                    log.warn("进程已退出: {}", instance.getPid());
                    return false;
                }
            } else {
                log.warn("实例未找到: {}", config.getAppImageId());
                return false;
            }
        }

        if (config.getHealthCheckUrl() != null && !config.getHealthCheckUrl().isEmpty()) {
            return performHttpHealthCheck(config);
        }

        log.debug("未配置健康检查 URL，仅检查进程存活");
        return true;
    }

    /**
     * 检查进程是否存活
     *
     * @param pid 进程 ID
     * @return true 进程存活
     */
    public boolean isProcessAlive(long pid) {
        if (pid <= 0) {
            return false;
        }
        return ProcessHandle.of(pid)
                .map(ProcessHandle::isAlive)
                .orElse(false);
    }

    /**
     * 通过 HTTP 健康检查接口检查
     *
     * @param config AppImage 运行时配置
     * @return true 健康检查通过
     */
    private boolean performHttpHealthCheck(AppImageRuntimeConfig config) {
        String healthCheckUrl = config.getHealthCheckUrl();
        int timeout = (int) Math.min(config.getHealthCheckInterval(), DEFAULT_TIMEOUT_MS);

        log.debug("HTTP 健康检查: {}，超时: {}ms", healthCheckUrl, timeout);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthCheckUrl))
                    .timeout(Duration.ofMillis(timeout))
                    .GET()
                    .build();

            CompletableFuture<HttpResponse<String>> responseFuture =
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

            HttpResponse<String> response = responseFuture.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);

            int statusCode = response.statusCode();
            String body = response.body();

            if (statusCode >= 200 && statusCode < 300) {
                if (body != null && body.contains(HEALTH_CHECK_SUCCESS)) {
                    log.debug("HTTP 健康检查通过: {}，状态码: {}", healthCheckUrl, statusCode);
                    return true;
                } else if (body != null) {
                    log.warn("健康检查响应内容异常: {}，响应: {}", healthCheckUrl, body.substring(0, Math.min(100, body.length())));
                    return false;
                } else {
                    log.debug("HTTP 健康检查通过（无响应体）: {}，状态码: {}", healthCheckUrl, statusCode);
                    return true;
                }
            } else {
                log.warn("HTTP 健康检查失败: {}，状态码: {}", healthCheckUrl, statusCode);
                return false;
            }
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("HTTP 健康检查超时: {}，超时: {}ms", healthCheckUrl, timeout);
            return false;
        } catch (Exception e) {
            log.error("HTTP 健康检查异常: {}", healthCheckUrl, e);
            return false;
        }
    }

    /**
     * 简单的 HTTP 健康检查
     *
     * @param healthCheckUrl 健康检查 URL
     * @param timeout        超时时间（毫秒）
     * @return true 健康检查通过
     */
    public boolean simpleHttpHealthCheck(String healthCheckUrl, int timeout) {
        AppImageRuntimeConfig config = new AppImageRuntimeConfig();
        config.setHealthCheckUrl(healthCheckUrl);
        config.setHealthCheckInterval(timeout);
        return performHttpHealthCheck(config);
    }
}
