package com.chua.spring.support.utils;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


/**
 * HTTP 响应工具类
 * <p>提供便捷方法获取当前请求的 HttpServletResponse 对象。</p>
 *
 * @author CH
 */
public class ResponseUtils {

    /**
     * 获取当前线程绑定的 HTTP 响应对象。
     * <p>该方法从 Spring 的请求上下文中提取 ServletRequestAttributes，
     * 进而获取 HttpServletResponse。如果在非 Web 环境或上下文中不存在请求信息，
     * 则返回 null。</p>
     *
     * @return HttpServletResponse 对象；如果无法获取（例如不在 Web 请求中），则返回 null
     */
    public static HttpServletResponse getResponse() {
        ServletRequestAttributes attr = null;
        try {
            // 获取当前请求的属性上下文
            attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            // 如果属性存在，则返回其关联的响应对象；否则返回 null
            return null != attr ? attr.getResponse() : null;
        } catch (Exception ignored) {
            // 捕获任何可能的异常（如空指针等），确保方法安全返回 null
        }
        return null;
    }

}
