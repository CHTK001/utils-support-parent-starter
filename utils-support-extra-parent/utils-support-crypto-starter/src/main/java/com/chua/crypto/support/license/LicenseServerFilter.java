package com.chua.crypto.support.license;

import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.nio.file.Path;

/**
 * 校验服务器下发过滤器（{@link ServerFilter} 体系实现）
 *
 * <p>绑定 {@code /license} 端点：接收加密包引导器的指纹查询，
 * 校验注册合法性后下发注册的私钥封装块，未注册返回 403。
 *
 * <h2>配置参数（init）</h2>
 * <ul>
 *   <li>{@code license.registry} — 注册表文件路径，默认 {@code licenses.txt}，
 *       格式每行 {@code fingerprintHex=base64(私钥封装块)}</li>
 * </ul>
 *
 * <p>协议（与 {@code launch.LicenseKeyClient} 对应）：
 * <pre>
 * POST /license  Body: {"appId":"..","fingerprint":"hex"}
 *   已注册 → 200 + 私钥封装块(application/octet-stream)
 *   未注册 → 403
 * </pre>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 注册：为指定指纹签发私钥文件
 * LicenseRegistry registry = LicenseRegistry.load(Path.of("licenses.txt"));
 * registry.register(fingerprint, keyBlob);
 *
 * // 启动校验服务
 * Server server = ServerBuilder.create().type("jdk").host("0.0.0.0").port(8641).build();
 * server.addFilter(new LicenseServerFilter(registry));
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
     * 注册表
     */
    private LicenseRegistry registry;

    /**
     * 默认构造：init 时从 {@code license.registry} 参数或默认路径 licenses.txt 加载
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
     * 初始化：未注入注册表时按配置路径加载
     */
    @Override
    public void init(ServerFilterConfig config) throws Exception {
        if (registry != null) {
            return;
        }
        String path = config == null ? null : config.getInitParameter("license.registry");
        this.registry = LicenseRegistry.load(Path.of(path == null || path.isBlank() ? DEFAULT_REGISTRY : path.trim()));
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
        response.setContentType("application/octet-stream").setBody(blob).end();
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
     * 从 JSON 请求体提取字段值（演示级简化解析）
     *
     * @param json  请求体
     * @param field 字段名
     * @return 值或 null
     */
    static String extract(String json, String field) {
        if (json == null) {
            return null;
        }
        int k = json.indexOf("\"" + field + "\"");
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k);
        int q1 = json.indexOf('"', colon);
        int q2 = q1 < 0 ? -1 : json.indexOf('"', q1 + 1);
        return q1 < 0 || q2 < 0 ? null : json.substring(q1 + 1, q2);
    }
}
