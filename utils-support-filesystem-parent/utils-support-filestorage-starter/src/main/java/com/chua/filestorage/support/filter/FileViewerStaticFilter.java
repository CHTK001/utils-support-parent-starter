package com.chua.filestorage.support.preview;

import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * file-viewer 静态资源过滤器。
 *
 * <p>从 classpath 读取 {@code file-viewer/} 资源并 Serve 到 {@code /file-viewer/} 路径。
 * 资源来自 {@code utils-support-resource-filestorage} 模块，通过 Maven 依赖注入。</p>
 *
 * <p>使用方式：将本过滤器注册到 {@link ServerFilterChain} 中，拦截 {@code /file-viewer/**} 路径。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FileViewerStaticFilter implements ServerFilter {

    private static final String ASSET_PATH_PREFIX = "/file-viewer/";

    /** MIME 类型映射，覆盖 WASM/Worker 等关键类型 */
    private static final Map<String, String> MIME_TYPES;
    static {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("js", "application/javascript");
        m.put("mjs", "application/javascript");
        m.put("wasm", "application/wasm");
        m.put("css", "text/css");
        m.put("json", "application/json");
        m.put("woff", "font/woff");
        m.put("woff2", "font/woff2");
        m.put("ttf", "font/ttf");
        m.put("otf", "font/otf");
        m.put("eot", "application/vnd.ms-fontobject");
        m.put("svg", "image/svg+xml");
        m.put("png", "image/png");
        m.put("jpg", "image/jpeg");
        m.put("gif", "image/gif");
        m.put("webp", "image/webp");
        m.put("bmp", "image/bmp");
        m.put("pdf", "application/pdf");
        m.put("txt", "text/plain");
        m.put("bcmap", "application/octet-stream");
        m.put("xml", "text/xml");
        m.put("html", "text/html");
        m.put("manifest", "application/x-web-app-manifest+json");
        MIME_TYPES = java.util.Collections.unmodifiableMap(m);
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        if (path == null || !path.startsWith(ASSET_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // 去掉前缀，得到资源相对路径（如 vendor/pdf/pdf.worker.mjs）
        String resourcePath = path.substring(ASSET_PATH_PREFIX.length());
        if (resourcePath.isEmpty() || resourcePath.contains("..")) {
            response.setStatus(400).end("Bad Request");
            return;
        }

        try (InputStream is = getClass().getClassLoader().getResourceAsStream("file-viewer/" + resourcePath)) {
            if (is == null) {
                chain.doFilter(request, response);
                return;
            }
            byte[] bytes = is.readAllBytes();
            String ext = getExt(resourcePath);
            String contentType = MIME_TYPES.getOrDefault(ext, "application/octet-stream");
            response.setStatus(200)
                    .setContentType(contentType)
                    .setHeader("Cache-Control", "public, max-age=86400")
                    .setBody(bytes)
                    .end();
        }
    }

    private static String getExt(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(dot + 1).toLowerCase() : "";
    }
}
