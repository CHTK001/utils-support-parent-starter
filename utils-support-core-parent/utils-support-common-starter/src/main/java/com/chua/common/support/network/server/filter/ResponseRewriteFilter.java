package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
* 响应改写过滤器，wrapping 模式：先放行链，返回后再改写响应。
*
* <p>解决 {@code end()} 被下游提前调用后无法改写的问题。
* 本 filter 在链首执行（order 最小），先调用 chain.doFilter() 放行，
* 链返回后（无论 end() 是否被调用）再执行改写逻辑。</p>
*
* <h2>改写能力</h2>
* <ul>
*   <li>条件匹配 — 按路径/状态码/Content-Type 等条件决定是否改写</li>
*   <li>状态码改写 — 如 404 → 200 + 自定义内容</li>
*   <li>响应头注入 — 添加/覆盖响应头（如 Server、X-Request-Id）</li>
*   <li>响应体替换 — 按条件替换响应体内容</li>
* </ul>
*
* <h2>使用示例</h2>
* <pre>{@code
* ResponseRewriteFilter filter = new ResponseRewriteFilter();
*
* // 1. 添加响应头
* filter.addHeader("Server", "MyServer/1.0");
* filter.addHeader("X-Request-Id", req -> UUID.randomUUID().toString());
*
* // 2. 条件改写：404 → 200 + 自定义页面
* filter.addRule(Rule.builder()
*     .condition(req -> req.getPath().startsWith("/app"))
*     .statusRewrite(status -> status == 404 ? 200 : status)
*     .bodyRewrite((req, body) -> "{\"fallback\":true}")
*     .build());
*
* // 3. CORS preflight 自动处理
* filter.addRule(Rule.builder()
*     .condition(req -> "OPTIONS".equals(req.getMethod().name()))
*     .statusRewrite(s -> 204)
*     .endImmediately(true)
*     .build());
* }</pre>
*
* @author CH
* @since 2026/07/18
 */
public class ResponseRewriteFilter implements ServerFilter {

    /**
    * 响应改写规则列表
     */
    private final java.util.List<RewriteRule> rules = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
    * 无条件注入的响应头
     */
    private final Map<String, BiConsumer<ServerRequest, ServerResponse>> globalHeaders = new ConcurrentHashMap<>();

    /**
    * 无条件注入的响应头（静态值）
     */
    private final Map<String, String> staticHeaders = new ConcurrentHashMap<>();

    // ==================== 响应头注入 ====================

    /**
    * 添加静态响应头（所有响应都会注入）。
    *
    * @param name  响应头名
    * @param value 响应头值
     */
    public void addHeader(String name, String value) {
        staticHeaders.put(name, value);
    }

    /**
    * 添加动态响应头（值由函数计算）。
    *
    * @param name      响应头名
    * @param valueFunc 值计算函数，接收请求，返回头值
     */
    public void addHeader(String name, BiConsumer<ServerRequest, ServerResponse> valueFunc) {
        globalHeaders.put(name, valueFunc);
    }

    // ==================== 改写规则 ====================

    /**
    * 添加改写规则。
    *
    * @param rule 改写规则
     */
    public void addRule(RewriteRule rule) {
        rules.add(rule);
    }

    // ==================== ServerFilter 实现 ====================

    /**
    * 最小 order，确保在链首执行（wrapping 模式）。
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 10;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    /**
    * Do过滤
    * @param request request
    * @param response response
    * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        // 先放行完整链路
        chain.doFilter(request, response);

        // 链返回后执行改写（无论 end() 是否被调用）
        applyRewrites(request, response);
    }

    /**
    * 执行所有改写逻辑。
    *
    * @param request  请求对象
    * @param response 响应对象
     */
    private void applyRewrites(ServerRequest request, ServerResponse response) {
        // 1. 注入全局响应头
        for (Map.Entry<String, String> entry : staticHeaders.entrySet()) {
            response.setHeader(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, BiConsumer<ServerRequest, ServerResponse>> entry : globalHeaders.entrySet()) {
            try {
                entry.getValue().accept(request, response);
            } catch (Exception e) {
                // 动态头计算失败，跳过
            }
        }

        // 2. 执行改写规则
        for (RewriteRule rule : rules) {
            if (rule.condition != null && !rule.condition.test(request)) {
                continue;
            }
            rule.apply(request, response);
        }
    }

    // ==================== 改写规则定义 ====================

    /**
    * 响应改写规则。
     */
    public static class RewriteRule {
        /**
        * 匹配条件：请求是否命中此规则
         */
        private Predicate<ServerRequest> condition;

        /**
        * 状态码改写函数：原状态码 → 新状态码
         */
        private java.util.function.IntUnaryOperator statusRewrite;

        /**
        * 响应体改写函数：(请求, 原体) → 新体
         */
        private java.util.function.BiFunction<ServerRequest, String, String> bodyRewrite;

        /**
        * 响应体改写函数（字节数组版本）
         */
        private java.util.function.BiFunction<ServerRequest, byte[], byte[]> bodyRewriteBytes;

        /**
        * 是否立即结束响应（跳过后续链）
         */
        private boolean endImmediately;

        /** 创建 RewriteRule 实例 */
        private RewriteRule() {}

        /** Builder */
        public static RewriteRule builder() {
            return new RewriteRule();
        }

        /** Condition */
        public RewriteRule condition(Predicate<ServerRequest> condition) {
            this.condition = condition;
            return this;
        }

        /** StatusRewrite */
        public RewriteRule statusRewrite(java.util.function.IntUnaryOperator statusRewrite) {
            this.statusRewrite = statusRewrite;
            return this;
        }

        /** BodyRewrite */
        public RewriteRule bodyRewrite(java.util.function.BiFunction<ServerRequest, String, String> bodyRewrite) {
            this.bodyRewrite = bodyRewrite;
            return this;
        }

        /** BodyRewriteBytes */
        public RewriteRule bodyRewriteBytes(java.util.function.BiFunction<ServerRequest, byte[], byte[]> bodyRewrite) {
            this.bodyRewriteBytes = bodyRewrite;
            return this;
        }

        /** EndImmediately */
        public RewriteRule endImmediately(boolean endImmediately) {
            this.endImmediately = endImmediately;
            return this;
        }

        /**
        * 应用此规则到响应。
        *
        * @param request  请求对象
        * @param response 响应对象
         */
        void apply(ServerRequest request, ServerResponse response) {
            // 状态码改写
            if (statusRewrite != null) {
                int newStatus = statusRewrite.applyAsInt(response.getStatus());
                response.setStatus(newStatus);
            }

            // 响应体改写
            if (bodyRewrite != null) {
                byte[] bodyData = response.getBody();
                String body = bodyData != null ? new String(bodyData, java.nio.charset.StandardCharsets.UTF_8) : "";
                if (body != null) {
                    String newBody = bodyRewrite.apply(request, body);
                    response.setBody(newBody);
                }
            } else if (bodyRewriteBytes != null) {
                byte[] body = response.getBody();
                if (body != null) {
                    byte[] newBody = bodyRewriteBytes.apply(request, body);
                    response.setBody(newBody);
                }
            }

            // 立即结束
            if (endImmediately && !response.isEnded()) {
                response.end();
            }
        }
    }
}
