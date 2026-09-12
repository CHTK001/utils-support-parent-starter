package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 通用静态资源服务过滤器。
 *
 * <p>将 classpath 或文件系统目录中的静态资源（Vue/React 构建产物、图片、JS/CSS 等）
 * 通过 HTTP 对外提供。支持配置 URL 前缀与资源根路径：</p>
 *
 * <pre>{@code
 * // 示例：提供 classpath 下 static/fs/ 目录，挂载在 /fs 前缀
 * StaticResourceServerFilter filter = new StaticResourceServerFilter("/fs", "static/fs");
 * }</pre>
 *
 * <p>行为：</p>
 * <ul>
 *   <li>{@code GET /fs/index.html} → classpath {@code static/fs/index.html}</li>
 *   <li>{@code GET /fs/} 或 {@code GET /fs} → 默认返回 {@code index.html}（SPA 壳）</li>
 *   <li>{@code GET /fs/assets/app.js} → classpath {@code static/fs/assets/app.js}</li>
 *   <li>资源不存在时继续走过滤器链（放行），供业务路由兜底</li>
 * </ul>
 *
 * <p>配置参数（通过 {@link #init(ServerFilterConfig)} 注入）：</p>
 * <ul>
 *   <li>{@code urlPrefix} — URL 前缀，默认 {@code /static}</li>
 *   <li>{@code resourcePath} — classpath 资源根目录，默认 {@code static}</li>
 *   <li>{@code fsRoot} — 可选文件系统根目录（优先于 classpath）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class StaticResourceServerFilter implements ServerFilter, ReactiveServerFilter {

    /**
     * URL 前缀（如 /fs）
     */
    private volatile String urlPrefix = "/static";

    /**
     * classpath 资源根目录（如 static）
     */
    private volatile String resourcePath = "static";

    /**
     * 文件系统根目录（可选，非空时优先读取磁盘文件）
     */
    private volatile Path fsRoot;

    /**
     * 索引文件名（SPA 壳）
     */
    private volatile String indexFile = "index.html";

    /**
     * 默认首页重定向（可选，如 /fs → /fs/）
     */
    private volatile boolean redirectToIndex = true;

    public StaticResourceServerFilter() {
        // NOTHING
    }

    /**
     * 创建过滤器。
     *
     * @param urlPrefix    URL 前缀（如 /fs）
     * @param resourcePath classpath 资源根目录（如 static）
     */
    public StaticResourceServerFilter(String urlPrefix, String resourcePath) {
        this.urlPrefix = normalizePrefix(urlPrefix);
        this.resourcePath = resourcePath == null || resourcePath.isEmpty() ? "static" : resourcePath;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    public String supportPath() {
        // Endpoint Filter：绑定 URL 前缀，仅匹配该前缀时触发
        return urlPrefix + "/**";
    }

    @Override
    public void init(ServerFilterConfig config) {
        if (config == null) {
            return;
        }
        String prefix = config.getInitParameter("urlPrefix");
        if (prefix != null && !prefix.isEmpty()) {
            this.urlPrefix = normalizePrefix(prefix);
        }
        String rp = config.getInitParameter("resourcePath");
        if (rp != null && !rp.isEmpty()) {
            this.resourcePath = rp;
        }
        String fs = config.getInitParameter("fsRoot");
        if (fs != null && !fs.isEmpty()) {
            this.fsRoot = Path.of(fs);
        }
    }

    @Override
    public void updateConfig(java.util.Map<String, Object> config) {
        if (config == null) {
            return;
        }
        if (config.containsKey("urlPrefix")) {
            this.urlPrefix = normalizePrefix(String.valueOf(config.get("urlPrefix")));
        }
        if (config.containsKey("resourcePath")) {
            this.resourcePath = String.valueOf(config.get("resourcePath"));
        }
        if (config.containsKey("fsRoot")) {
            this.fsRoot = Path.of(String.valueOf(config.get("fsRoot")));
        }
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        CompletionStage<Void> stage = doReactiveFilter(request, response, null);
        stage.toCompletableFuture().join();
        if (!response.isEnded()) {
            chain.doFilter(request, response);
        }
    }

    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response, ReactiveFilterChain chain) {
        return doReactiveFilter(request, response, chain);
    }

    /**
     * 执行过滤逻辑。
     */
    private CompletionStage<Void> doReactiveFilter(ServerRequest request, ServerResponse response, ReactiveFilterChain chain) {
        String path = request.getPath();
        if (path == null || !path.startsWith(urlPrefix)) {
            return chain == null
                    ? CompletableFuture.completedFuture(null)
                    : passthrough(request, response, chain);
        }
        HttpMethod method = request.getMethod();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            return chain == null
                    ? CompletableFuture.completedFuture(null)
                    : passthrough(request, response, chain);
        }

        // /fs 或 /fs/ → index.html（SPA 壳）
        String relative = path.substring(urlPrefix.length());
        if (relative.isEmpty() || "/".equals(relative)) {
            relative = "/" + indexFile;
        }
        // 规范化：去前导斜杠 + 防目录穿越
        String resourceKey = relative.startsWith("/") ? relative.substring(1) : relative;
        if (resourceKey.contains("..")) {
            response.sendError(400, "Bad Request");
            return CompletableFuture.completedFuture(null);
        }

        byte[] data = loadResource(resourceKey);
        if (data == null) {
            // 资源不存在放行后续链
            return chain == null
                    ? CompletableFuture.completedFuture(null)
                    : passthrough(request, response, chain);
        }

        boolean assets = resourceKey.startsWith("assets/");
        response.setStatus(200)
                .setContentType(guessContentType(resourceKey))
                .setHeader("Cache-Control", assets ? "public, max-age=31536000" : "no-cache")
                .setBody(data)
                .end();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 加载资源：优先文件系统，其次 classpath。
     *
     * @param resourceKey 资源相对路径
     * @return 内容字节，不存在返回 null
     */
    private byte[] loadResource(String resourceKey) {
        if (fsRoot != null) {
            try {
                Path target = fsRoot.resolve(resourceKey).normalize();
                if (target.startsWith(fsRoot.normalize()) && Files.isRegularFile(target)) {
                    return Files.readAllBytes(target);
                }
            } catch (IOException ignored) {
                // 回退 classpath
            }
        }
        String classpathKey = resourcePath + "/" + resourceKey;
        try (InputStream in = StaticResourceServerFilter.class.getResourceAsStream("/" + classpathKey)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 推断静态资源 Content-Type。
     */
    private String guessContentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html;charset=UTF-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript;charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css;charset=UTF-8";
        if (lower.endsWith(".json")) return "application/json;charset=UTF-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        if (lower.endsWith(".map")) return "application/json";
        if (lower.endsWith(".txt")) return "text/plain;charset=UTF-8";
        if (lower.endsWith(".xml")) return "application/xml;charset=UTF-8";
        return "application/octet-stream";
    }

    /**
     * 规范化 URL 前缀（确保以 / 开头、不以 / 结尾）。
     */
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "/static";
        }
        String p = prefix.startsWith("/") ? prefix : "/" + prefix;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 放行到后续过滤器链。
     */
    private static CompletionStage<Void> passthrough(ServerRequest request, ServerResponse response, ReactiveFilterChain chain) {
        try {
            return chain.doFilter(request, response);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
