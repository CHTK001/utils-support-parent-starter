package com.chua.common.support.network.http;


/**
 * HTTP 状态码常量与分类判断工具类。
 *
 * <p>提供标准 HTTP 状态码常量及状态码分类判断方法。
 * 状态码分为五大类：</p>
 * <ul>
 *   <li><b>1xx 信息响应</b> — 请求已接收，继续处理（如 100 Continue、101 Switching Protocols）</li>
 *   <li><b>2xx 成功响应</b> — 请求成功接收并处理（如 200 OK、201 Created、204 No Content）</li>
 *   <li><b>3xx 重定向</b> — 需要进一步操作以完成请求（如 301 Moved Permanently、302 Found）</li>
 *   <li><b>4xx 客户端错误</b> — 请求包含错误或无法处理（如 400 Bad Request、401 Unauthorized、404 Not Found）</li>
 *   <li><b>5xx 服务端错误</b> — 服务端处理请求时出错（如 500 Internal Server Error、503 Service Unavailable）</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * int statusCode = response.getStatusCode();
 *
 * if (HttpStatus.isSuccess(statusCode)) {
 *     // 处理成功响应（2xx）
 * } else if (HttpStatus.isRedirect(statusCode)) {
 *     // 处理重定向（3xx）
 * } else if (HttpStatus.isClientError(statusCode)) {
 *     // 处理客户端错误（4xx）
 * } else if (HttpStatus.isServerError(statusCode)) {
 *     // 处理服务端错误（5xx）
 * }
 * }</pre>
 *
 * <p><b>说明：</b>本类为工具类，构造函数私有，禁止实例化，只能通过静态方法调用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see ClientResponse#isSuccess()
 */
public class HttpStatus {

    // ==================== 2xx 成功 ====================

    /** 200 OK — 请求成功。标准响应，表示请求已被成功处理。 */
    public static final int OK = 200;
    /** 201 Created — 资源创建成功。通常在 POST 请求后返回，表示新资源已创建。 */
    public static final int CREATED = 201;
    /** 204 No Content — 请求成功但无返回内容。常用于 DELETE 操作成功后返回。 */
    public static final int NO_CONTENT = 204;

    // ==================== 4xx 客户端错误 ====================

    /** 400 Bad Request — 请求参数错误。服务器无法理解请求的格式。 */
    public static final int BAD_REQUEST = 400;
    /** 401 Unauthorized — 未认证。请求需要用户身份验证。 */
    public static final int UNAUTHORIZED = 401;
    /** 403 Forbidden — 无权限。服务器理解请求但拒绝执行。 */
    public static final int FORBIDDEN = 403;
    /** 404 Not Found — 资源不存在。服务器找不到请求的资源。 */
    public static final int NOT_FOUND = 404;

    // ==================== 5xx 服务端错误 ====================

    /** 500 Internal Server Error — 服务器内部错误。服务器遇到意外情况无法完成请求。 */
    public static final int INTERNAL_SERVER_ERROR = 500;
    /** 503 Service Unavailable — 服务暂不可用。服务器当前无法处理请求（通常为过载或维护）。 */
    public static final int SERVICE_UNAVAILABLE = 503;

    /**
     * 私有构造方法，防止外部实例化。
     *
     * <p>本类为静态工具类，所有方法均为静态方法，无需实例化。
     */
    private HttpStatus() {}

    /**
     * 判断是否为成功状态码（2xx）。
     *
     * <p>2xx 状态码表示请求已成功被服务器接收、理解和处理。
     * 包括但不限于：200 OK、201 Created、204 No Content、206 Partial Content。
     *
     * @param code HTTP 状态码
     * @return 状态码在 200-299 范围内返回 true，否则返回 false
     */
    public static boolean isSuccess(int code) {
        return code >= 200 && code < 300;
    }

    /**
     * 判断是否为重定向状态码（3xx）。
     *
     * <p>3xx 状态码表示需要客户端采取进一步操作才能完成请求。
     * 包括但不限于：301 Moved Permanently、302 Found、304 Not Modified、307 Temporary Redirect。
     *
     * @param code HTTP 状态码
     * @return 状态码在 300-399 范围内返回 true，否则返回 false
     */
    public static boolean isRedirect(int code) {
        return code >= 300 && code < 400;
    }

    /**
     * 判断是否为客户端错误状态码（4xx）。
     *
     * <p>4xx 状态码表示请求包含错误或无法被服务器处理，
     * 通常是由于客户端请求格式有误、未认证、无权限或资源不存在等原因。
     * 包括但不限于：400 Bad Request、401 Unauthorized、403 Forbidden、404 Not Found、429 Too Many Requests。
     *
     * @param code HTTP 状态码
     * @return 状态码在 400-499 范围内返回 true，否则返回 false
     */
    public static boolean isClientError(int code) {
        return code >= 400 && code < 500;
    }

    /**
     * 判断是否为服务端错误状态码（5xx）。
     *
     * <p>5xx 状态码表示服务器在处理请求时发生错误或异常，
     * 通常是服务器内部错误、网关超时或服务不可用等原因。
     * 包括但不限于：500 Internal Server Error、502 Bad Gateway、503 Service Unavailable、504 Gateway Timeout。
     *
     * @param code HTTP 状态码
     * @return 状态码在 500-599 范围内返回 true，否则返回 false
     */
    public static boolean isServerError(int code) {
        return code >= 500;
    }
}
