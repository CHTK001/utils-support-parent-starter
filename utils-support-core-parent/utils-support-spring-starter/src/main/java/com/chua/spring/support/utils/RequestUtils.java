package com.chua.spring.support.utils;

import com.chua.common.support.utils.BeanUtils;
import com.chua.common.support.utils.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;


/**
 * 请求工具类，提供IP地址获取、请求头读取、会话信息管理等功能
 *
 * @author CH
 * @since 2023-08-01
 */
@Slf4j
public class RequestUtils {

    /**
     * 会话中存储用户名的键
     */
    public static final String SESSION_USERNAME = "x-session-token-username";

    /**
     * 会话中存储租户 ID 的键
     */
    public static final String SESSION_TENANT_ID = "x-session-token-tenantId";

    /**
     * 会话中存储用户 ID 的键
     */
    public static final String SESSION_USERID = "x-session-token-userid";

    /**
     * 会话中存储用户信息的键
     */
    public static final String SESSION_USER_INFO = "x-session-token-userxxx";

    /**
     * 请求头中存储租户 ID 的键
     */
    private static final String HEADER_TENANT_ID = "x-oauth-tenant";

    /**
     * 本地 IP 地址缓存，用于在检测到本地回环地址时返回实际本机IP
     */
    static String LOCAL = null;

    static {
        // 初始化本地IP地址缓存
        InetAddress inet = null;
        try {
            inet = InetAddress.getLocalHost();
        } catch (Exception e) {
            log.error("获取本机IP地址失败", e);
        }
        if (inet != null) {
            LOCAL = inet.getHostAddress();
        } else {
            LOCAL = "127.0.0.1";
        }
    }

    /**
     * 获取客户端 IP 地址
     * 优先从请求上下文中获取，若无法获取则返回 "::"
     *
     * @return 客户端 IP 地址，获取失败返回 "::"
     * @example getIpAddress() => "192.168.1.100"
     */
    public static String getIpAddress() {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return "::";
        }

        return getIpAddress(request);
    }

    /**
     * 获取客户端 IP 地址
     * 支持通过代理服务器（如Nginx）转发后的真实IP获取逻辑
     * 依次检查 x-forwarded-for, Proxy-Client-IP, WL-Proxy-Client-IP 等头信息
     * 若为本地回环地址，则返回实际配置的本地IP
     *
     * @param request HTTP 请求对象
     * @return 客户端 IP 地址，请求为空返回空字符串
     * @example getIpAddress(request) => "192.168.1.100"
     */
    public static String getIpAddress(HttpServletRequest request) {
        if (null == request) {
            return "";
        }

        // 优先获取 X-Forwarded-For 头中的第一个IP（通常是真实客户端IP）
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        // 如果上述代理头均未设置，则直接获取远程地址
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
            // 如果是本地回环地址，返回实际本机IP
            if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
                return LOCAL;
            }
        }
        // 处理 x-forwarded-for 可能包含多个IP的情况（格式：IP1, IP2, IP3...）
        // 只取第一个IP作为真实客户端IP
        if (ip != null && ip.length() > 15) {
            if (ip.indexOf(",") > 0) {
                ip = ip.substring(0, ip.indexOf(","));
            }
        }
        return StringUtils.defaultString(ip, request.getRemoteAddr());
    }

    /**
     * 获取指定名称的 HTTP 请求头值
     *
     * @param request    HTTP 请求对象
     * @param headerName 请求头名称
     * @return 请求头值，请求为空返回 null
     * @example getHeader(request, "User-Agent") => "Mozilla/5.0 ..."
     */
    public static String getHeader(HttpServletRequest request, String headerName) {
        if (null == request) {
            return null;
        }

        return request.getHeader(headerName);
    }

    /**
     * 获取当前线程绑定的 HTTP 请求对象
     * 该方法依赖于 Spring 的 RequestContextHolder 上下文
     *
     * @return HTTP 请求对象，获取失败返回 null
     * @example getRequest() => HttpServletRequest 对象
     */
    public static HttpServletRequest getRequest() {
        try {
            ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return null != attr ? attr.getRequest() : null;
        } catch (Exception ignored) {
            // 捕获异常并忽略，防止非Web环境下调用报错
        }
        return null;
    }

    /**
     * 获取请求的 URL 路径（URI部分）
     *
     * @param request HTTP 请求对象
     * @return 请求的 URL 路径，请求为空返回 null
     * @example getUrl(request) => "/api/users"
     */
    public static String getUrl(HttpServletRequest request) {
        if (null == request) {
            return null;
        }

        return request.getRequestURI();
    }

    /**
     * 判断给定的主机地址是否为本地地址
     * 支持 IPv4 (127.0.0.1), IPv6 (::1), 和 localhost 域名
     *
     * @param hostAddress 主机地址
     * @return 本地地址返回 true，否则返回 false
     * @example isLocal("127.0.0.1") => true
     */
    public static boolean isLocal(String hostAddress) {
        return "127.0.0.1".equals(hostAddress) ||
                "0:0:0:0:0:0:0:1".equals(hostAddress) ||
                "localhost".equals(hostAddress);
    }

    /**
     * 从会话中获取租户 ID
     * 依赖 Session 中预先设置的 SESSION_TENANT_ID 属性
     *
     * @return 租户 ID，获取失败返回 null
     * @example getTenantId() => "tenant_001"
     */
    public static String getTenantId() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return null;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        Object attribute = request.getSession().getAttribute(SESSION_TENANT_ID);
        return null == attribute ? null : attribute.toString();
    }

    /**
     * 从会话中获取用户 ID
     * 依赖 Session 中预先设置的 SESSION_USERID 属性
     *
     * @return 用户 ID，获取失败返回 null
     * @example getUserId() => "user_001"
     */
    public static String getUserId() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return null;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        Object attribute = request.getSession().getAttribute(SESSION_USERID);
        return null == attribute ? null : attribute.toString();
    }

    /**
     * 从会话中获取当前用户名
     * 依赖 Session 中预先设置的 SESSION_USERNAME 属性
     *
     * @return 当前用户名，获取失败返回 null
     * @example getUsername() => "admin"
     */
    public static String getUsername() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return null;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        Object attribute = request.getSession().getAttribute(SESSION_USERNAME);
        return null == attribute ? null : attribute.toString();
    }

    /**
     * 从会话中移除租户 ID
     */
    public static void removeTenantId() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        request.getSession().removeAttribute(SESSION_TENANT_ID);
    }

    /**
     * 设置租户 ID 到当前会话中
     *
     * @param tenantId 租户 ID 值
     * @example setTenantId("tenant_001")
     */
    public static void setTenantId(String tenantId) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        request.getSession().setAttribute(SESSION_TENANT_ID, tenantId);
    }

    /**
     * 从会话中移除用户 ID
     */
    public static void removeUserId() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        request.getSession().removeAttribute(SESSION_USERID);
    }

    /**
     * 设置用户 ID 到当前会话中
     *
     * @param userId 用户 ID 值
     * @example setUserId("user_001")
     */
    public static void setUserId(String userId) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        request.getSession().setAttribute(SESSION_USERID, userId);
    }

    /**
     * 设置当前用户名到会话中
     *
     * @param username 用户名
     * @example setUsername("admin")
     */
    public static void setUsername(String username) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        request.getSession().setAttribute(SESSION_USERNAME, username);
    }

    /**
     * 设置用户信息对象到会话中
     *
     * @param userInfo 用户信息对象
     * @example setUserInfo(userObject)
     */
    public static void setUserInfo(Object userInfo) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        request.getSession().setAttribute(SESSION_USER_INFO, userInfo);
    }

    /**
     * 获取用户信息并转换为指定类型
     * 如果已存在且类型匹配，直接返回；否则使用 BeanUtils 进行属性拷贝转换
     *
     * @param target 目标 Class 类型
     * @param <T>    泛型类型
     * @return 用户信息对象，获取失败返回 null
     * @example getUserInfo(UserDto.class) => UserDto 对象
     */
    @SuppressWarnings("ALL")
    public static <T> T getUserInfo(Class<T> target) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return null;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        Object attribute = request.getSession().getAttribute(SESSION_USER_INFO);
        if (null == attribute) {
            return null;
        }

        // 如果已有对象且是目标类型的实例，直接强转返回
        if (null != attribute && target.isAssignableFrom(attribute.getClass())) {
            return (T) attribute;
        }

        // 否则进行属性拷贝转换
        return BeanUtils.copyProperties(attribute, target);
    }

    /**
     * 从会话中移除用户名
     * 注意：原代码此处错误地移除了 USER_INFO，现保持与原代码逻辑一致（可能是个bug，但指令要求不动逻辑只加注释）
     * 根据原代码实现，这里实际上移除的是 SESSION_USER_INFO
     */
    public static void removeUsername() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        request.getSession().removeAttribute(SESSION_USER_INFO);
    }

    /**
     * 从会话中移除用户信息
     */
    public static void removeUserInfo() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }

        ServletRequestAttributes attributes = (ServletRequestAttributes) requestAttributes;
        HttpServletRequest request = attributes.getRequest();
        request.getSession().removeAttribute(SESSION_USER_INFO);
    }

    /**
     * 判断请求 URI 是否为静态资源
     * 检测常见的静态资源后缀 (.js, .css, .html, .icon) 或特定路径 (/preview/, /download/)
     *
     * @param requestUri 请求 URI 路径
     * @return 是静态资源返回 true，否则返回 false
     * @example isResource("/static/js/app.js") => true
     */
    public static boolean isResource(String requestUri) {
        return requestUri.contains(".js")
                || requestUri.contains(".css")
                || requestUri.contains("/preview/")
                || requestUri.contains("/download/")
                || requestUri.contains(".html")
                || requestUri.contains(".icon");
    }
}
