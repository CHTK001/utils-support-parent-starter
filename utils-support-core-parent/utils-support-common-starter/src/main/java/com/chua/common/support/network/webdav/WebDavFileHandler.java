// [P3C A20 豁免] A20/A30/A31: 含日志/协议状态行等非业务魔法值场景，豁免。
package com.chua.common.support.network.webdav;

import com.chua.common.support.network.server.filter.ReactiveFilterChain;
import com.chua.common.support.network.server.filter.ReactiveServerFilter;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import com.chua.common.support.utils.ObjectUtils;
import com.chua.common.support.utils.CollectionUtils;

/**
 * WebDAV 请求处理器（纯 JDK 实现，基于文件系统存储）。
 *
 * <p>以 {@link ReactiveServerFilter} 形式接入服务器过滤器链：
 * 命中 WebDAV 方法集（OPTIONS/GET/HEAD/PUT/DELETE/MKCOL/PROPFIND/PROPPATCH/
 * COPY/MOVE/LOCK/UNLOCK）时直接完成响应，其余方法放行后续过滤器。</p>
 *
 * <p>实现为最小可用集合：PROPFIND 支持 Depth 0/1，LOCK/UNLOCK 为咨询式
 * （不真正阻塞并发写），PROPPATCH 统一返回成功状态。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
class WebDavFileHandler implements ReactiveServerFilter {

    /**
     * 匿名用户名
     */
    private static final String ANONYMOUS = "anonymous";

    /**
     * HTTP 日期格式（RFC 1123，GMT）
     */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    /**
     * 服务器配置
     */
    private final WebDavConfig config;

    /**
     * 存储根目录（绝对路径、已归一化）
     */
    private final Path root;

    /**
     * 创建处理器。
     *
     * @param config WebDAV 配置
     */
    WebDavFileHandler(WebDavConfig config) {
        this.config = config;
        try {
            Files.createDirectories(config.getHomeDirectory().toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create home directory: "
                    + config.getHomeDirectory(), e);
        }
        this.root = config.getHomeDirectory().getAbsoluteFile().toPath().normalize();
    }

    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        String method = request.getMethod() == null ? "" : request.getMethod().name();
        try {
            switch (method) {
                case "OPTIONS" -> handleOptions(request, response);
                case "GET", "HEAD" -> handleRead(request, response, "HEAD".equals(method));
                case "PUT" -> handlePut(request, response);
                case "DELETE" -> handleDelete(request, response);
                case "MKCOL" -> handleMkcol(request, response);
                case "PROPFIND" -> handlePropfind(request, response);
                case "PROPPATCH" -> handleProppatch(request, response);
                case "COPY", "MOVE" -> handleCopyMove(request, response, "MOVE".equals(method));
                case "LOCK" -> handleLock(request, response);
                case "UNLOCK" -> response.setStatus(204);
                default -> {
                    return chain.doFilter(request, response);
                }
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            log.warn("WebDAV 处理失败: {} {} - {}", method, request.getPath(), e.getMessage());
            response.setStatus(500);
            response.setContentType("text/plain;charset=UTF-8");
            response.setBody("WebDAV Error: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * OPTIONS：声明 DAV 能力。
     */
    private void handleOptions(ServerRequest request, ServerResponse response) {
        response.setStatus(204);
        response.setHeader("DAV", "1, 2");
        response.setHeader("MS-Author-Via", "DAV");
        response.setHeader("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, "
                + "PROPPATCH, COPY, MOVE, LOCK, UNLOCK");
    }

    /**
     * GET/HEAD：下载文件；目录返回简单 HTML 列表。
     */
    private void handleRead(ServerRequest request, ServerResponse response, boolean headOnly)
            throws IOException {
        String user = authenticate(request);
        if (user == null) {
            unauthorized(response);
            return;
        }
        Path path = resolve(request.getPath());
        if (path == null || !Files.exists(path)) {
            response.sendError(404, "Not Found: " + request.getPath());
            return;
        }
        if (Files.isDirectory(path)) {
            response.setStatus(200);
            response.setContentType("text/html;charset=UTF-8");
            if (!headOnly) {
                response.setBody(directoryListing(request.getPath(), path));
            }
            return;
        }
        byte[] data = Files.readAllBytes(path);
        response.setStatus(200);
        response.setContentType(probeContentType(path));
        response.setHeader("Last-Modified", httpDate(Files.getLastModifiedTime(path).toMillis()));
        response.setHeader("ETag", etag(data));
        if (!headOnly) {
            response.setBody(data);
        }
    }

    /**
     * PUT：上传/覆盖文件（父目录必须存在）。
     */
    private void handlePut(ServerRequest request, ServerResponse response) throws IOException {
        String user = authenticate(request);
        if (!canWrite(user)) {
            forbidden(response, user);
            return;
        }
        Path path = resolve(request.getPath());
        if (path == null || path.equals(root)) {
            response.sendError(403, "Invalid resource path");
            return;
        }
        if (Files.isDirectory(path)) {
            response.sendError(405, "Cannot PUT to a collection");
            return;
        }
        Path parent = path.getParent();
        if (parent == null || !Files.exists(parent)) {
            response.sendError(409, "Parent collection does not exist");
            return;
        }
        boolean created = !Files.exists(path);
        byte[] body = request.getBody() == null ? new byte[0] : request.getBody();
        Files.write(path, body);
        log.info("WebDAV PUT: {} -> {} ({} bytes) by {}", request.getPath(), path, body.length, user);
        response.setStatus(created ? 201 : 204);
        response.setHeader("ETag", etag(body));
    }

    /**
     * DELETE：删除文件或目录（递归）。
     */
    private void handleDelete(ServerRequest request, ServerResponse response) throws IOException {
        String user = authenticate(request);
        if (!canWrite(user)) {
            forbidden(response, user);
            return;
        }
        Path path = resolve(request.getPath());
        if (path == null || path.equals(root) || !Files.exists(path)) {
            response.sendError(404, "Not Found: " + request.getPath());
            return;
        }
        deleteRecursively(path);
        log.info("WebDAV DELETE: {} ({}) by {}", request.getPath(), path, user);
        response.setStatus(204);
    }

    /**
     * MKCOL：创建集合（目录）。
     */
    private void handleMkcol(ServerRequest request, ServerResponse response) throws IOException {
        String user = authenticate(request);
        if (!canWrite(user)) {
            forbidden(response, user);
            return;
        }
        Path path = resolve(request.getPath());
        if (path == null || path.equals(root)) {
            response.sendError(403, "Invalid collection path");
            return;
        }
        if (Files.exists(path)) {
            response.sendError(405, "Collection already exists");
            return;
        }
        Path parent = path.getParent();
        if (parent == null || !Files.exists(parent)) {
            response.sendError(409, "Parent collection does not exist");
            return;
        }
        Files.createDirectory(path);
        log.info("WebDAV MKCOL: {} ({}) by {}", request.getPath(), path, user);
        response.setStatus(201);
    }

    /**
     * PROPFIND：返回资源的属性（Depth 0/1）。
     */
    private void handlePropfind(ServerRequest request, ServerResponse response) throws IOException {
        String user = authenticate(request);
        if (user == null) {
            unauthorized(response);
            return;
        }
        Path path = resolve(request.getPath());
        if (path == null || !Files.exists(path)) {
            response.sendError(404, "Not Found: " + request.getPath());
            return;
        }
        String depth = request.getHeader("Depth");
        int d = "0".equalsIgnoreCase(depth == null ? "1" : depth.trim()) ? 0 : 1;
        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<D:multistatus xmlns:D=\"DAV:\">\n");
        appendPropResponse(xml, request.getPath(), path);
        if (d >= 1 && Files.isDirectory(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    String href = joinPath(request.getPath(), child.getFileName().toString());
                    appendPropResponse(xml, href, child);
                }
            }
        }
        xml.append("</D:multistatus>\n");
        response.setStatus(207);
        response.setContentType("application/xml;charset=UTF-8");
        response.setBody(xml.toString());
    }

    /**
     * PROPPATCH：设置属性（最小实现：统一返回 200 状态）。
     */
    private void handleProppatch(ServerRequest request, ServerResponse response) throws IOException {
        String user = authenticate(request);
        if (!canWrite(user)) {
            forbidden(response, user);
            return;
        }
        Path path = resolve(request.getPath());
        if (path == null || !Files.exists(path)) {
            response.sendError(404, "Not Found: " + request.getPath());
            return;
        }
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<D:multistatus xmlns:D=\"DAV:\">\n")
                .append("  <D:response>\n")
                .append("    <D:href>").append(xmlEscape(href(request.getPath()))).append("</D:href>\n")
                .append("    <D:propstat>\n")
                .append("      <D:prop/>\n")
                .append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
                .append("    </D:propstat>\n")
                .append("  </D:response>\n")
                .append("</D:multistatus>\n");
        response.setStatus(207);
        response.setContentType("application/xml;charset=UTF-8");
        response.setBody(xml.toString());
    }

    /**
     * COPY/MOVE：按 Destination 头复制/移动资源。
     */
    private void handleCopyMove(ServerRequest request, ServerResponse response, boolean move)
            throws IOException {
        String user = authenticate(request);
        if (!canWrite(user)) {
            forbidden(response, user);
            return;
        }
        Path source = resolve(request.getPath());
        if (source == null || !Files.exists(source)) {
            response.sendError(404, "Not Found: " + request.getPath());
            return;
        }
        String destination = request.getHeader("Destination");
        if (destination == null || destination.isBlank()) {
            response.sendError(400, "Missing 'Destination' header");
            return;
        }
        Path target = resolve(extractPath(destination));
        if (target == null || target.equals(root)) {
            response.sendError(403, "Invalid destination");
            return;
        }
        if (target.equals(source)) {
            response.sendError(403, "Source and destination are the same");
            return;
        }
        Path targetParent = target.getParent();
        if (targetParent == null || !Files.exists(targetParent)) {
            response.sendError(409, "Destination parent collection does not exist");
            return;
        }
        boolean overwrite = !"F".equalsIgnoreCase(firstHeader(request, "Overwrite"));
        if (Files.exists(target)) {
            if (!overwrite) {
                response.sendError(412, "Destination already exists");
                return;
            }
            deleteRecursively(target);
        }
        if (move) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (UncheckedIOException | IOException e) {
                copyRecursively(source, target);
                deleteRecursively(source);
            }
        } else {
            copyRecursively(source, target);
        }
        log.info("WebDAV {}: {} -> {} by {}", move ? "MOVE" : "COPY", request.getPath(),
                target, user);
        response.setStatus(201);
    }

    /**
     * LOCK：返回咨询式写锁（不真正阻塞并发写）。
     */
    private void handleLock(ServerRequest request, ServerResponse response) throws IOException {
        String user = authenticate(request);
        if (!canWrite(user)) {
            forbidden(response, user);
            return;
        }
        Path path = resolve(request.getPath());
        if (path == null || !Files.exists(path)) {
            response.sendError(404, "Not Found: " + request.getPath());
            return;
        }
        String token = "opaquelocktoken:" + UUID.randomUUID();
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<D:prop xmlns:D=\"DAV:\">\n")
                .append("  <D:lockdiscovery>\n")
                .append("    <D:activelock>\n")
                .append("      <D:locktype><D:write/></D:locktype>\n")
                .append("      <D:lockscope><D:exclusive/></D:lockscope>\n")
                .append("      <D:depth>0</D:depth>\n")
                .append("      <D:timeout>Second-3600</D:timeout>\n")
                .append("      <D:locktoken><D:href>").append(token).append("</D:href></D:locktoken>\n")
                .append("      <D:owner>").append(xmlEscape(user)).append("</D:owner>\n")
                .append("    </D:activelock>\n")
                .append("  </D:lockdiscovery>\n")
                .append("</D:prop>\n");
        response.setStatus(200);
        response.setHeader("Lock-Token", "<" + token + ">");
        response.setContentType("application/xml;charset=UTF-8");
        response.setBody(xml.toString());
    }

    /**
     * 解析 Basic 认证。
     *
     * @return 用户名；匿名访问返回 {@link #ANONYMOUS}；认证失败返回 {@code null}
     */
    private String authenticate(ServerRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return config.isAnonymousEnabled() ? ANONYMOUS : null;
        }
        String scheme = authorization.trim();
        if (!scheme.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(scheme.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int sep = decoded.indexOf(':');
        String username = sep < 0 ? decoded : decoded.substring(0, sep);
        String password = sep < 0 ? "" : decoded.substring(sep + 1);
        Map<String, String> users = config.getUsers();
        if (CollectionUtils.isEmpty(users)) {
            // 无用户数据库：任意凭据放行（仅建议开发/测试环境使用）
            return username;
        }
        String expected = users.get(username);
        return ObjectUtils.nullSafeEquals(expected, password) ? username : null;
    }

    /**
     * 是否可写。
     */
    private boolean canWrite(String user) {
        if (user == null) {
            return false;
        }
        if (!ANONYMOUS.equals(user)) {
            return true;
        }
        return config.isAnonymousEnabled() && config.isAllowAnonymousWrite();
    }

    /**
     * 401 响应。
     */
    private void unauthorized(ServerResponse response) {
        response.setStatus(401);
        response.setHeader("WWW-Authenticate", "Basic realm=\"webdav\"");
        response.setContentType("text/plain;charset=UTF-8");
        response.setBody("Unauthorized");
    }

    /**
     * 403 响应。
     */
    private void forbidden(ServerResponse response, String user) {
        response.setStatus(403);
        response.setContentType("text/plain;charset=UTF-8");
        response.setBody("Forbidden: " + (user == null ? "authentication required"
                : "user '" + user + "' is not allowed to write"));
    }

    /**
     * 将请求路径解析为根目录内的安全路径；越界返回 {@code null}。
     */
    private Path resolve(String path) {
        if (path == null) {
            return root;
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        try {
            p = URLDecoder.decode(p, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignore) {
            // 保持原始路径
        }
        Path resolved = root.resolve(p).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    /**
     * 从 Destination/绝对 URI 中提取路径。
     */
    private String extractPath(String uri) {
        String u = uri.trim();
        int scheme = u.indexOf("://");
        if (scheme >= 0) {
            int pathStart = u.indexOf('/', scheme + 3);
            u = pathStart >= 0 ? u.substring(pathStart) : "/";
        }
        return u;
    }

    /**
     * 拼接父路径与子名。
     */
    private String joinPath(String parent, String name) {
        String p = parent == null ? "/" : parent;
        return p.endsWith("/") ? p + name : p + "/" + name;
    }

    /**
     * 生成 href（目录以 / 结尾）。
     */
    private String href(String path) {
        String p = path == null ? "/" : path;
        return p.startsWith("/") ? p : "/" + p;
    }

    /**
     * 追加单个资源的 propstat XML。
     */
    private void appendPropResponse(StringBuilder xml, String path, Path file) throws IOException {
        boolean directory = Files.isDirectory(file);
        String displayName = file.getFileName() == null ? "/" : file.getFileName().toString();
        xml.append("  <D:response>\n")
                .append("    <D:href>").append(xmlEscape(href(path)
                        + (directory && !path.endsWith("/") ? "/" : ""))).append("</D:href>\n")
                .append("    <D:propstat>\n")
                .append("      <D:prop>\n")
                .append("        <D:displayname>").append(xmlEscape(displayName)).append("</D:displayname>\n");
        if (directory) {
            xml.append("        <D:resourcetype><D:collection/></D:resourcetype>\n");
        } else {
            xml.append("        <D:resourcetype/>\n")
                    .append("        <D:getcontentlength>").append(Files.size(file))
                    .append("</D:getcontentlength>\n")
                    .append("        <D:getcontenttype>").append(xmlEscape(probeContentType(file)))
                    .append("</D:getcontenttype>\n")
                    .append("        <D:getetag>").append(xmlEscape(etag(Files.readAllBytes(file))))
                    .append("</D:getetag>\n");
        }
        xml.append("        <D:getlastmodified>")
                .append(httpDate(Files.getLastModifiedTime(file).toMillis()))
                .append("</D:getlastmodified>\n")
                .append("      </D:prop>\n")
                .append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
                .append("    </D:propstat>\n")
                .append("  </D:response>\n");
    }

    /**
     * 目录 HTML 列表。
     */
    private String directoryListing(String path, Path dir) throws IOException {
        StringBuilder html = new StringBuilder(512);
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
                .append(xmlEscape(path)).append("</title></head><body><h1>")
                .append(xmlEscape(path)).append("</h1><ul>\n");
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                html.append("<li><a href=\"").append(xmlEscape(name))
                        .append(Files.isDirectory(child) ? "/" : "").append("\">")
                        .append(xmlEscape(name)).append("</a></li>\n");
            }
        }
        html.append("</ul></body></html>");
        return html.toString();
    }

    /**
     * 递归删除。
     */
    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * 递归复制。
     */
    private void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                for (Path child : children) {
                    copyRecursively(child, target.resolve(child.getFileName().toString()));
                }
            }
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 探测内容类型。
     */
    private String probeContentType(Path file) {
        try {
            String type = Files.probeContentType(file);
            return type == null ? "application/octet-stream" : type;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    /**
     * 取第一个指定头。
     */
    private String firstHeader(ServerRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value.trim();
    }

    /**
     * 内容 ETag（弱化处理：MD5 十六进制）。
     */
    private String etag(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] md5 = digest.digest(data);
            StringBuilder hex = new StringBuilder(32);
            for (byte b : md5) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return "\"" + hex + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            return "\"" + data.length + "\"";
        }
    }

    /**
     * HTTP 日期。
     */
    private String httpDate(long epochMillis) {
        return HTTP_DATE.format(ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC));
    }

    /**
     * XML 转义。
     */
    private String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
