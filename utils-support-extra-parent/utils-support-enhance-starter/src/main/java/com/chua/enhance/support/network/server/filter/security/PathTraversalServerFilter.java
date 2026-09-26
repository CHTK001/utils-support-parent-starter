package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;

import java.io.File;
import java.io.IOException;
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
 *   <li>按 {@code /} 与 {@code \} 分段后存在 {@code ..} 段</li>
 *   <li>配置 {@code pathTraversal.root} 时，解析到根目录外的请求路径</li>
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
     * 静态资源根目录，未配置时跳过规范化越界校验
     */
    private volatile String rootPath;

    @Override
    /**
     * 初始化
    */
    public void init(ServerFilterConfig config) throws Exception {
        this.rootPath = config.getInitParameter("pathTraversal.root");
    }

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
        String decoded;
        try {
            decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 残缺的百分号编码无法还原，按非法路径处理
            response.end(400, "{\"error\":\"Bad Request\",\"message\":\"非法路径访问\"}");
            return;
        }
        if (isTraversal(decoded)) {
            response.end(400, "{\"error\":\"Bad Request\",\"message\":\"非法路径访问\"}");
            return;
        }
        if (isOutsideRoot(decoded)) {
            response.end(400, "{\"error\":\"Bad Request\",\"message\":\"非法路径访问\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 判断请求路径解析后是否逃出配置的根目录。
     *
     * <p>请求路径是 URI 而非文件系统路径，因此必须与根目录拼接后再规范化；
     * 未配置 {@code pathTraversal.root} 时跳过该项校验。</p>
     *
     * @param path 已解码的请求路径
     * @return 明确越界时返回 {@code true}
     */
    private boolean isOutsideRoot(String path) {
        if (rootPath == null || rootPath.isEmpty()) {
            return false;
        }
        try {
            File root = new File(rootPath).getCanonicalFile();
            return !new File(root, path).getCanonicalFile().toPath().startsWith(root.toPath());
        } catch (IOException e) {
            // 根目录本身不可解析属于配置错误，不能因此放行或误拦
            return false;
        }
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
        for (String segment : path.split("[/\\\\]")) {
            if (TRAVERSAL_PATTERN.equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
