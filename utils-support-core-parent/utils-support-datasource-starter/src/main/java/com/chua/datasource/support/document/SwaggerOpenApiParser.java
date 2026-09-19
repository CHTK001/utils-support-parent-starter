package com.chua.datasource.support.document;

import com.chua.common.support.lang.document.*;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Swagger / 打开api 文档解析器。
 *
 * <p>支持两种输入模式：</p>
 * <ul>
 *   <li>{@code type=swagger}：从 classpath 资源路径加载 OpenAPI JSON（如 {@code openapi/orders-api.json}）</li>
 *   <li>{@code type=openapi}：从 HTTP URL 拉取 OpenAPI 规范</li>
 * </ul>
 *
 * <p>输出 {@link OpenApiDocumentData}，由 {@link OpenApiHtmlProvider} 渲染为单页 HTML。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@Spi({"swagger", "openapi"})
public class SwaggerOpenApiParser implements DocumentParser {

    @Override
    public DocumentData parse(DocumentConfig config) {
        String url = config.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("DocumentConfig.url 不能为空");
        }

        Map<String, String> overrides = new HashMap<>();
        if (config.getOptions() != null) {
            config.getOptions().forEach((k, v) -> {
                if (v != null) {
                    overrides.put(k, v.toString());
                }
            });
        }

        boolean isHttp = url.startsWith("http://") || url.startsWith("https://");
        OpenApiDocumentData data;
        if (isHttp) {
            data = parseFromUrl(url, overrides);
        } else {
            data = parseFromClasspath(url, overrides);
        }
        return data;
    }

 // ── 公共 静态 entry points ─────────────────────────

    /**
     * 从 打开api JSON 字符串解析为文档数据。
     * @param json json
     * @param overrides overrides
     * @return 解析的结果
     */
    public static OpenApiDocumentData parse(String json, Map<String, String> overrides) {
        OpenApiDocumentData data = new OpenApiDocumentData();
        if (json == null || json.isBlank()) {
            return data;
        }

        try {
            JsonObject root = Json.getJsonObject(json);

 // 基础 信息
            String specVersion = root.getType("openapi", "", String.class);
            if (specVersion.isBlank()) {
                specVersion = root.getType("swagger", "", String.class);
            }
            if (specVersion.isBlank()) {
                specVersion = "3.0.0";
            }

            JsonObject info = root.getJsonObject("info");
            applyOverride(data, overrides, "title",     info.getType("title", "", String.class));
            applyOverride(data, overrides, "version",   info.getType("version", "", String.class));
            applyOverride(data, overrides, "description", info.getType("description", "", String.class));
            applyOverrides(data, overrides);

 // 标签
            JsonArray tagsArr = root.getJsonArray("tags");
            List<OpenApiTag> tags = new ArrayList<>();
            if (tagsArr != null) {
                for (int i = 0; i < tagsArr.size(); i++) {
                    JsonObject t = tagsArr.getJsonObject(i);
                    if (t != null) {
                        OpenApiTag tag = new OpenApiTag();
                        tag.setName(t.getType("name", "", String.class));
                        tag.setDescription(t.getType("description", null, String.class));
                        tags.add(tag);
                    }
                }
            }
            data.setTags(tags);

 // 路径 → 端点
            List<OpenApiEndpoint> endpoints = new ArrayList<>();
            JsonObject paths = root.getJsonObject("paths");
            if (paths != null) {
                paths.forEach((path, pathObj) -> {
                    if (!(pathObj instanceof JsonObject pathNode)) {
                        return;
                    }
                    pathNode.forEach((methodStr, opObj) -> {
                        String method = methodStr.toUpperCase(Locale.ENGLISH);
                        if (!isHttpMethod(method)) {
                            return;
                        }
                        if (!(opObj instanceof JsonObject op)) {
                            return;
                        }

                        OpenApiEndpoint ep = new OpenApiEndpoint();
                        ep.setMethod(method);
                        ep.setPath(path);
                        ep.setDescription(op.getType("summary", null, String.class));
                        ep.setSummary(op.getType("description", null, String.class));
                        if ((ep.getSummary() == null || ep.getSummary().isBlank()) && ep.getDescription() != null) {
                            ep.setSummary(ep.getDescription());
                        }

 // 标签
                        JsonArray epTags = op.getJsonArray("tags");
                        if (epTags != null && epTags.size() > 0) {
                            ep.setTag(epTags.getJsonObject(0).getType("name", "", String.class));
                        } else {
                            ep.setTag(resolveDefaultTag(path));
                        }

 // 参数
                        List<OpenApiParam> params = new ArrayList<>();
                        collectParams(op, params);
                        ep.setParameters(params);

 // 请求主体
                        JsonObject rb = op.getJsonObject("requestBody");
                        if (rb != null) {
                            ep.setRequestBody(parseRequestBody(rb));
                        }

 // 响应
                        List<OpenApiResponse> responses = new ArrayList<>();
                        JsonObject respNode = op.getJsonObject("responses");
                        if (respNode != null) {
                            parseResponses(respNode, responses);
                        }
                        ep.setResponses(responses);

                        endpoints.add(ep);
                    });
                });
            }
            data.setEndpoints(endpoints);

 // 安全性 schemes → section
            JsonObject components = root.getJsonObject("components");
            if (components != null) {
                JsonObject secSchemes = components.getJsonObject("securitySchemes");
                if (secSchemes != null && !secSchemes.isEmpty()) {
                    String securityMd = renderSecuritySection(secSchemes);
                    if (!securityMd.isBlank()) {
                        OpenApiSection sec = new OpenApiSection();
                        sec.setTitle("鉴权与安全");
                        sec.setContent(securityMd);
                        data.getSections().add(sec);
                    }
                }
            }

 // 服务端 → 基础 URL section
            JsonArray servers = root.getJsonArray("servers");
            if (servers != null && !servers.isEmpty()) {
                StringBuilder sb = new StringBuilder("## 基础 URL\n\n");
                for (int i = 0; i < servers.size(); i++) {
                    JsonObject srv = servers.getJsonObject(i);
                    if (srv != null) {
                        String srvUrl = srv.getType("url", "", String.class);
                        String desc = srv.getType("description", null, String.class);
                        sb.append("- `").append(srvUrl).append("`");
                        if (desc != null && !desc.isBlank()) {
                            sb.append(" — ").append(desc);
                        }
                        sb.append("\n");
                    }
                }
                OpenApiSection sec = new OpenApiSection();
                sec.setTitle("基础 URL");
                sec.setContent(sb.toString());
                data.getSections().add(sec);
            }

        } catch (Exception e) {
            log.error("Failed to parse OpenAPI JSON", e);
            throw new RuntimeException("OpenAPI 解析失败: " + e.getMessage(), e);
        }
        return data;
    }

    /**
     * 解析从类路径。
     * @param resourcePath resource路径
     * @param overrides overrides
     * @return 解析从类路径的结果
     */
    public static OpenApiDocumentData parseFromClasspath(String resourcePath, Map<String, String> overrides) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SwaggerOpenApiParser.class.getClassLoader();
        }
        InputStream is = cl.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalStateException("OpenAPI 资源不存在: " + resourcePath);
        }
        String json;
        try (InputStream inner = is) {
            json = new String(inner.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("读取 OpenAPI 资源失败: " + resourcePath, e);
        }
        return parse(json, overrides);
    }

    /**
     * 解析从url。
     * @param url url
     * @param overrides overrides
     * @return 解析从url的结果
     */
    public static OpenApiDocumentData parseFromUrl(String url, Map<String, String> overrides) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code + " from " + url);
            }
            String json;
            try (InputStream is = conn.getInputStream()) {
                json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            conn.disconnect();
            return parse(json, overrides);
        } catch (Exception e) {
            throw new RuntimeException("拉取 OpenAPI 失败: " + url, e);
        }
    }

 // ── 助手 ──────────────────────────────────────────────

    /**
     * 是否http方法。
     * @param m m
     * @return 是否http方法的结果
     */
    private static boolean isHttpMethod(String m) {
        return "GET".equals(m) || "POST".equals(m) || "PUT".equals(m)
                || "DELETE".equals(m) || "PATCH".equals(m) || "HEAD".equals(m)
                || "OPTIONS".equals(m);
    }

    private static void applyOverride(OpenApiDocumentData data, Map<String, String> overrides,
                                      String key, String defaultValue) {
        if (overrides != null && overrides.containsKey(key)) {
            String v = overrides.get(key);
            if      ("title".equals(key)) {
                data.setTitle(v);
            }
            else if ("version".equals(key)) {
                data.setVersion(v);
            }
            else if ("description".equals(key)) {
                data.setDescription(v);
            }
        } else if (defaultValue != null) {
            if      ("title".equals(key)) {
                data.setTitle(defaultValue);
            }
            else if ("version".equals(key)) {
                data.setVersion(defaultValue);
            }
            else if ("description".equals(key)) {
                data.setDescription(defaultValue);
            }
        }
    }

    /**
     * applyoverrides。
     * @param data 数据
     * @param overrides overrides
     */
    private static void applyOverrides(OpenApiDocumentData data, Map<String, String> overrides) {
        if (overrides == null) {
            return;
        }
        if (overrides.containsKey("title")) {
            data.setTitle(overrides.get("title"));
        }
        if (overrides.containsKey("version")) {
            data.setVersion(overrides.get("version"));
        }
        if (overrides.containsKey("description")) {
            data.setDescription(overrides.get("description"));
        }
    }

    /**
     * collect参数。
     * @param op op
     * @param params 参数
     */
    private static void collectParams(JsonObject op, List<OpenApiParam> params) {
 // Operation-级别 参数
        JsonArray paramsArr = op.getJsonArray("parameters");
        if (paramsArr != null) {
            for (int i = 0; i < paramsArr.size(); i++) {
                JsonObject p = paramsArr.getJsonObject(i);
                if (p != null) {
                    params.add(parseParam(p));
                }
            }
        }
    }

    /**
     * 解析参数。
     * @param node 节点
     * @return 解析参数的结果
     */
    private static OpenApiParam parseParam(JsonObject node) {
        OpenApiParam p = new OpenApiParam();
        p.setName(node.getType("name", "", String.class));
        p.setIn(node.getType("in", "query", String.class));
        JsonObject schema = node.getJsonObject("schema");
        String type = schema != null ? schema.getType("type", null, String.class) : node.getType("type", null, String.class);
        p.setType(type != null ? type : "string");
        p.setRequired(Boolean.TRUE.equals(node.getType("required", false, Boolean.class)));
        p.setDescription(node.getType("description", null, String.class));
        p.setExample(node.getType("example", null, String.class));
        return p;
    }

    /**
     * 解析请求主体。
     * @param node 节点
     * @return 解析请求主体的结果
     */
    private static OpenApiRequestBody parseRequestBody(JsonObject node) {
        OpenApiRequestBody body = new OpenApiRequestBody();
        body.setRequired(Boolean.TRUE.equals(node.getType("required", false, Boolean.class)));
        body.setDescription(node.getType("description", null, String.class));

        JsonObject content = node.getJsonObject("content");
        if (content != null) {
            content.forEach((ct, ctNode) -> {
                if (body.getContentType() == null || "application/json".equals(body.getContentType())) {
                    body.setContentType(ct);
                }
                if (ctNode instanceof JsonObject ctObj && ctObj.hasKey("schema")) {
                    JsonObject schema = ctObj.getJsonObject("schema");
                    if (schema != null) {
                        String t = schema.getType("type", null, String.class);
                        if (t != null) {
                            body.setType(t);
                        }
                    }
                }
                if (ctNode instanceof JsonObject ctObj && ctObj.hasKey("example")) {
                    body.setExample(ctObj.get("example").toString());
                }
            });
        }
        return body;
    }

    /**
     * 解析响应。
     * @param responsesNode 响应节点
     * @param responses 响应
     */
    private static void parseResponses(JsonObject responsesNode, List<OpenApiResponse> responses) {
        responsesNode.forEach((codeStr, respNode) -> {
            if (!(respNode instanceof JsonObject respObj)) {
                return;
            }
            OpenApiResponse resp = new OpenApiResponse();
            resp.setCode(codeStr);
            resp.setDescription(respObj.getType("description", null, String.class));

            JsonObject content = respObj.getJsonObject("content");
            if (content != null) {
                content.forEach((ct, ctNode) -> {
                    if (!(ctNode instanceof JsonObject ctObj)) {
                        return;
                    }
                    if (ctObj.hasKey("schema")) {
                        JsonObject schema = ctObj.getJsonObject("schema");
                        if (schema != null && schema.hasKey("properties")) {
                            JsonObject props = schema.getJsonObject("properties");
                            if (props != null) {
                                List<OpenApiParam> fields = new ArrayList<>();
                                props.forEach((fieldName, fieldNode) -> {
                                    if (!(fieldNode instanceof JsonObject f)) {
                                        return;
                                    }
                                    OpenApiParam fParam = new OpenApiParam();
                                    fParam.setName(fieldName);
                                    fParam.setType(f.getType("type", "string", String.class));
                                    fParam.setDescription(f.getType("description", null, String.class));
                                    fParam.setExample(f.getType("example", null, String.class));
                                    fields.add(fParam);
                                });
                                resp.setFields(fields);
                            }
                        }
                    }
                    if (ctNode instanceof JsonObject ctObj2 && ctObj2.hasKey("example")) {
                        resp.setExample(ctObj2.get("example").toString());
                    }
                });
            }
            responses.add(resp);
        });
    }

    /**
     * resolve默认标签。
     * @param path 路径
     * @return resolve默认标签的结果
     */
    private static String resolveDefaultTag(String path) {
        String[] parts = path.stripLeading().split("/");
        if (parts.length > 1 && !parts[1].startsWith("{")) {
            return parts[1];
        }
        return "default";
    }

    /**
     * render安全性section。
     * @param schemes schemes
     * @return render安全性section的结果
     */
    private static String renderSecuritySection(JsonObject schemes) {
        StringBuilder sb = new StringBuilder();
        schemes.forEach((name, scheme) -> {
            if (!(scheme instanceof JsonObject s)) {
                return;
            }
            String type = s.getType("type", "", String.class);
            String desc = s.getType("description", null, String.class);
            sb.append("### ").append(name).append("\n\n");
            sb.append("- **类型**: ").append(type).append("\n");
            if (desc != null && !desc.isBlank()) {
                sb.append("- **说明**: ").append(desc).append("\n");
            }

            if ("http".equals(type) && s.hasKey("scheme")) {
                sb.append("\n```\nAuthorization: Bearer <token>\n```\n\n");
            } else if ("apiKey".equals(type)) {
                String in = s.getType("in", "header", String.class);
                String key = s.getType("name", "X-API-Key", String.class);
                sb.append("\n```\nin: ").append(in).append("  \nname: ").append(key).append("\n```\n\n");
            }
        });
        return sb.toString();
    }
}
