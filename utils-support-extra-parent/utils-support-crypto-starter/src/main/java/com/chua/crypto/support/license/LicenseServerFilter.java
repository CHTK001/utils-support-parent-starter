package com.chua.crypto.support.license;

import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 校验服务器下发过滤器（{@link ServerFilter} 体系实现）
 *
 * <p>绑定 {@code /license} 端点：接收加密包引导器的指纹查询，
 * 校验注册合法性后下发注册的私钥封装块，未注册返回 403。
 *
 * <h2>配置参数（init）</h2>
 * <ul>
 *   <li>{@code license.registry} — 注册表文件路径，默认 {@code licenses.txt}</li>
 *   <li>{@code license.secret} — 响应签名密钥；配置后响应格式为
 *       {@code v1.base64(块).base64(HmacSHA256(secret,块))}，
 *       客户端须以相同 {@code chua.crypto.license-secret} 校验（生产必须配置）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * LicenseRegistry registry = FileLicenseRegistry.load(Path.of("licenses.txt"));
 * registry.register(fingerprint, keyBlob);
 *
 * Server server = ServerBuilder.create().type("jdk").host("0.0.0.0").port(8641).build();
 * server.addFilter(new LicenseServerFilter(registry, "prod-secret".toCharArray()));
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 2026-08-26
 */
public class LicenseServerFilter implements ServerFilter {

    /**
     * 默认注册表文件路径
     */
    private static final String DEFAULT_REGISTRY = "licenses.txt";

    /**
     * 签名响应版本前缀（与 launch.LicenseKeyClient 对应）
     */
    private static final String SIGNED_PREFIX = "v1.";

    /**
     * 注册表
     */
    private LicenseRegistry registry;

    /**
     * 响应签名密钥（为空则不下发签名）
     */
    private char[] secret;

    /**
     * 默认构造：init 时从参数/默认路径加载注册表
     */
    public LicenseServerFilter() {
    }

    /**
     * 注册表注入构造（编程装配场景，init 不再覆盖）
     *
     * @param registry 已加载的注册表
     */
    public LicenseServerFilter(LicenseRegistry registry) {
        this.registry = registry;
    }

    /**
     * 全参构造：注册表 + 响应签名密钥（生产推荐）
     *
     * @param registry       已加载的注册表
     * @param responseSecret 响应签名密钥（客户端 chua.crypto.license-secret 须一致）
     */
    public LicenseServerFilter(LicenseRegistry registry, char[] responseSecret) {
        this.registry = registry;
        this.secret = responseSecret == null ? null : responseSecret.clone();
    }

    /**
     * 初始化：未注入注册表时按配置路径加载；读取签名密钥
     */
    @Override
    public void init(ServerFilterConfig config) throws Exception {
        if (registry == null) {
            String path = config == null ? null : config.getInitParameter("license.registry");
            this.registry = FileLicenseRegistry.load(
                    Path.of(path == null || path.isBlank() ? DEFAULT_REGISTRY : path.trim()));
        }
        if (secret == null && config != null) {
            String s = config.getInitParameter("license.secret");
            if (s != null && !s.isBlank()) {
                this.secret = s.trim().toCharArray();
            }
        }
    }

    /**
     * 处理校验请求：已注册下发私钥封装块并终止链，未注册 403
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        if (!"POST".equalsIgnoreCase(String.valueOf(request.getMethod()))) {
            response.setStatus(405).end();
            return;
        }
        String fingerprint = extract(request.getBodyString(), "fingerprint");
        byte[] blob = fingerprint == null ? null : registry.lookup(fingerprint);

        if (blob == null) {
            response.setStatus(403).end();
            return;
        }
        byte[] payload = secret == null || secret.length == 0
                ? blob
                : (SIGNED_PREFIX + Base64.getEncoder().encodeToString(blob)
                        + "." + Base64.getEncoder().encodeToString(hmac(blob)))
                .getBytes(StandardCharsets.UTF_8);
        response.setContentType("application/octet-stream").setBody(payload).end();
    }

    /**
     * 绑定路径
     */
    @Override
    public String supportPath() {
        return "/license";
    }

    /**
     * 高优先级执行
     */
    @Override
    public int getOrder() {
        return 10;
    }

    /**
     * 计算响应签名 HmacSHA256(secret, blob)
     *
     * @param blob 私钥封装块
     * @return 摘要
     */
    private byte[] hmac(byte[] blob) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new String(secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(blob);
        } catch (Exception e) {
            throw new IllegalStateException("响应签名计算失败", e);
        }
    }

    /**
     * 从 JSON 请求体提取指纹字段（严格匹配 64 位十六进制，防注入）
     *
     * @param json 请求体
     * @param field 字段名
     * @return 值或 null
     */
    static String extract(String json, String field) {
        if (json == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
