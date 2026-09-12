package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
* XSS 防护过滤器，对请求参数和请求体进行 XSS 特殊字符转义。
*
* <p>将 HTML 特殊字符（{@code < > " ' &}）转义为 HTML 实体，
* 防止跨站脚本攻击。仅对文本类型的请求体进行处理。
*
* <h2>转义规则</h2>
* <ul>
*   <li>{@code <} → {@code &lt;}</li>
*   <li>{@code >} → {@code &gt;}</li>
*   <li>{@code "} → {@code &quot;}</li>
*   <li>{@code '} → {@code &#x27;}</li>
*   <li>{@code &} → {@code &amp;}</li>
* </ul>
*
* @author CH
* @since 2026/07/16
 */
public class XssServerFilter implements ServerFilter {

    /**
    * XSS 脚本标签正则
     */
    private static final Pattern SCRIPT_PATTERN = Pattern.compile(
            "<\\s*script[^>]*>.*?<\\s*/\\s*script[^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
    * XSS 事件属性正则
     */
    private static final Pattern EVENT_PATTERN = Pattern.compile(
            "\\bon\\w+\\s*=\\s*[\"'][^\"']*[\"']",
            Pattern.CASE_INSENSITIVE);

    @Override
    /** 执行过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        byte[] body = request.getBody();
        if (body != null && body.length > 0) {
            String contentType = request.getContentType();
            if (isTextContent(contentType)) {
                String bodyStr = new String(body, StandardCharsets.UTF_8);
                String sanitized = sanitize(bodyStr);
                if (!sanitized.equals(bodyStr)) {
                    request.setAttribute("xss.sanitizedBody", sanitized.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return 30;
    }

    @Override
    /** 获取过滤标识 */
    public String getFilterId() {
        return "XssServerFilter";
    }

    /**
    * 是否文本内容
    *
    * @param contentType 内容类型
    * @return 是否文本内容的结果
     */
    private boolean isTextContent(String contentType) {
        if (contentType == null) {
            return true;
        }
        String lower = contentType.toLowerCase();
        return lower.contains("text") || lower.contains("json") || lower.contains("xml")
                || lower.contains("form-urlencoded");
    }

    /**
    * Sanitize
    *
    * @param input 输入
    * @return sanitize的结果
     */
    private String sanitize(String input) {
        if (input == null) {
            return null;
        }
        String result = SCRIPT_PATTERN.matcher(input).replaceAll("");
        result = EVENT_PATTERN.matcher(result).replaceAll("");
        return result;
    }
}