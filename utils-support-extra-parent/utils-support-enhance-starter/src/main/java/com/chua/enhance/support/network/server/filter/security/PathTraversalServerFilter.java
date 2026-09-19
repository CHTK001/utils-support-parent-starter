package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 路径遍历防护过滤器，阻止目录穿越攻击。
 *
 * <p>检查请求路径中是否包含 {@code ..} 等路径遍历特征字符，
 * 同时对解码后的路径和规范化路径进行二次校验，防止编码绕过。
 *
 * <h2>检测规则</h2>
 * <ul>
 *   <li>原始路径包含 {@code ..}</li>
 *   <li>URL 解码后包含 {@code ..}</li>
 *   <li>规范化路径前缀不匹配根目录</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
public class PathTraversalServerFilter implements ServerFilter {

    /**
     * 路径遍历特征字符串
     */
    private static final String TRAVERSAL_PATTERN = "..";

    /**
     * 当前工作目录，用于规范化路径校验
     */
    private static final String ROOT_PATH = new File("").getAbsolutePath();

    @Override
    /**
     * 执行过滤
    */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        if (path == null) {
            chain.doFilter(request, response);
            return;
        }
        if (isTraversal(path)) {
            response.end(400, "{\"error\":\"Bad Request\",\"message\":\"非法路径访问\"}");
            return;
        }
        String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (isTraversal(decoded)) {
            response.end(400, "{\"error\":\"Bad Request\",\"message\":\"非法路径访问\"}");
            return;
        }
        String normalized = new File(decoded).getCanonicalPath();
        if (!normalized.startsWith(ROOT_PATH)) {
            response.end(400, "{\"error\":\"Bad Request\",\"message\":\"非法路径访问\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /**
     * 获取订单
    */
    public int getOrder() {
        return 8;
    }

    @Override
    /**
     * 获取过滤标识
    */
    public String getFilterId() {
        return "PathTraversalServerFilter";
    }

    /**
     * 是否Traversal
     *
     * @param path 路径
     * @return 是否traversal的结果
     */
    private boolean isTraversal(String path) {
        return path.contains(TRAVERSAL_PATTERN) || path.contains("\\\\");
    }
}
