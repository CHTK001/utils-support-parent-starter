package com.chua.filestorage.support.preview;

import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
* 文件-viewer 静态资源过滤器。
*
* <p>从 classpath 读取 {@code file-viewer/} 资源并 Serve 到路径。
* 支持带版本号的 URL（{@code /file-viewer/{version}/...}）和不带版本号的 URL（{@code /file-viewer/...}）。
* 大文件（WebAssembly/工人/字体）启用 immutable 缓存 + e标签，减少服务器交互。</p>
*
* <p>缓存策略：
* <ul>
*   <li>JS/CSS/WASM/字体：{@code Cache-Control: public, immutable, max-age=31536000}（1年永久缓存）
* 配合 e标签，内容变化后浏览器自动重新下载。</li>
*   <li>小文件（bcmap/json/svg 等）：{@code Cache-Control: public, max-age=86400}（24小时）。</li>
* </ul>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class FileViewerStaticFilter implements ServerFilter {

    /** 版本路径前缀：/文件-viewer/{版本}/ */
    private static final String VERSION_PREFIX_PATTERN = "/file-viewer/[^/]+/";

    /** MIME 类型映射 */
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

    /** 需要 long-ttl + immutable 缓存的文件类型（WebAssembly/工人/字体/主 JS bundle） */
    private static final java.util.Set<String> IMMUTABLE_EXTS;
    static {
        java.util.Set<String> s = new java.util.HashSet<>();
        s.add("wasm"); s.add("js"); s.add("mjs");
        s.add("woff"); s.add("woff2"); s.add("ttf"); s.add("otf"); s.add("eot");
        IMMUTABLE_EXTS = java.util.Collections.unmodifiableSet(s);
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        if (path == null || !path.startsWith("/file-viewer")) {
            chain.doFilter(request, response);
            return;
        }

        // 兼容两种路径格式：
 // 1. /文件-viewer/{版本}/供应商/...  （版本路径，新版本）
 // 2. /文件-viewer/供应商/...             （无前缀路径，老版本兼容）
        String resourcePath;
        if (path.matches(VERSION_PREFIX_PATTERN + ".*")) {
 // 去掉 /文件-viewer/{版本}/
            int secondSlash = path.indexOf('/', 14); // skip "/file-viewer/"
            resourcePath = path.substring(secondSlash + 1);
        } else {
            resourcePath = path.substring("/file-viewer/".length());
        }

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

 // 计算 e标签（基于文件内容 MD5）
            String etag = computeEtag(bytes);
            String ifNoneMatch = request.getHeader("If-None-Match");
            if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
                response.setStatus(304)
                        .setHeader("ETag", etag)
                        .end();
                return;
            }

            boolean immutable = IMMUTABLE_EXTS.contains(ext);
            response.setStatus(200)
                    .setContentType(contentType)
                    .setHeader("ETag", etag)
                    .setHeader("Cache-Control", immutable
                            ? "public, immutable, max-age=31536000"
                            : "public, max-age=86400")
                    .setBody(bytes)
                    .end();
        }
    }

    /**
    * computeetag。
    * @param bytes bytes
    * @return computeEtag的结果
     */
    private static String computeEtag(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(34);
            sb.append('"');
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            sb.append('"');
            return sb.toString();
        } catch (Exception e) {
            return "\"fallback\"";
        }
    }

    /**
    * 获取ext。
    * @param path 路径
    * @return 获取ext的结果
     */
    private static String getExt(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(dot + 1).toLowerCase() : "";
    }
}
