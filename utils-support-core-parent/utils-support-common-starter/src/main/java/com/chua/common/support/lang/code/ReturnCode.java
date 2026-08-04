package com.chua.common.support.lang.code;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * 统一返回状态码枚举
 * <p>
 * <b>编码格式</b>: {@code [大类前缀][主HTTP段][次前缀][子序号]}, 共 10 位。
 * <br>例如 {@code S0500C0400} 表示: 服务端(S) 500 错误, 关联客户端(C) 400 场景。
 * <p>
 * <b>大类前缀</b>:
 * <ul>
 *   <li>{@code 00000}: 成功</li>
 *   <li>{@code Cxxxx}: 客户端错误 (Client)</li>
 *   <li>{@code Sxxxx}: 服务端错误 (Server)</li>
 *   <li>{@code Axxxx}: 认证/授权错误 (Auth)</li>
 *   <li>{@code Dxxxx}: 数据错误 (Data)</li>
 *   <li>{@code Bxxxx}: 业务错误 (Business)</li>
 *   <li>{@code Fxxxx}: 文件错误 (File)</li>
 * </ul>
 *
 * @author CH
 * @since 2023-04-01
 */
@NullMarked
@Getter
@AllArgsConstructor
public enum ReturnCode implements ResultCode {

    // ==================== 成功状态 ====================

    /**
     * 操作成功
     */
    OK("00000", "操作成功"),
    /**
     * 操作成功(OK 别名)
     */
    SUCCESS("00000", "操作成功"),

    // ==================== 客户端错误 (C) ====================

    /**
     * 请求参数异常
     */
    REQUEST_PARAM_ERROR("C0400S0000", "请求参数异常"),
    /**
     * 请求参数为空
     */
    REQUEST_PARAM_EMPTY("C0400S0001", "请求参数不能为空"),
    /**
     * 请求参数格式错误
     */
    REQUEST_PARAM_FORMAT_ERROR("C0400S0002", "请求参数格式错误"),
    /**
     * 请求参数超出范围
     */
    REQUEST_PARAM_OUT_OF_RANGE("C0400S0003", "请求参数超出范围"),
    /**
     * 用户名或密码错误
     */
    USERNAME_OR_PASSWORD_ERROR("C0400S0004", "用户名或密码错误"),
    /**
     * 验证码错误
     */
    RESOURCE_CAPTCHA_ERROR("C0400S0005", "验证码错误"),
    /**
     * 验证码已过期
     */
    CAPTCHA_EXPIRED("C0400S0010", "验证码已过期"),
    /**
     * 远程服务器执行超时
     */
    REMOTE_EXECUTION_TIMEOUT("C0400S0503", "远程服务器执行超时"),
    /**
     * 请求频率超限
     */
    REQUEST_RATE_LIMIT("C0429S0000", "请求频率超限, 请稍后重试"),
    /**
     * 其他客户端错误
     */
    CLIENT_OTHER_ERROR("C9999S0000", "其他错误"),

    // ==================== 认证/授权错误 (A) ====================

    /**
     * 未登录或登录已过期
     */
    RESULT_ACCESS_UNAUTHORIZED("A0401S0000", "未登录或登录已过期"),
    /**
     * 用户未登录
     */
    USER_NOT_LOGIN("A0401S0001", "用户未登录"),
    /**
     * 登录已过期
     */
    LOGIN_EXPIRED("A0401S0002", "登录已过期, 请重新登录"),
    /**
     * Token 无效
     */
    TOKEN_INVALID("A0401S0003", "Token无效"),
    /**
     * Token 已过期
     */
    TOKEN_EXPIRED("A0401S0004", "Token已过期"),
    /**
     * 权限不足
     */
    RESOURCE_OAUTH_ERROR("A0403S0000", "权限不足"),
    /**
     * 无访问权限
     */
    ACCESS_DENIED("A0403S0001", "无访问权限"),
    /**
     * 账号已被禁用
     */
    ACCOUNT_DISABLED("A0403S0002", "账号已被禁用"),
    /**
     * 账号已被锁定
     */
    ACCOUNT_LOCKED("A0403S0003", "账号已被锁定"),
    /**
     * 账号已过期
     */
    ACCOUNT_EXPIRED("A0403S0004", "账号已过期"),
    /**
     * 密码已过期
     */
    PASSWORD_EXPIRED("A0403S0005", "密码已过期, 请修改密码"),

    // ==================== 资源不存在 (404) ====================

    /**
     * 服务器不存在
     */
    SYSTEM_SERVER_NOT_FOUND("S0404C0000", "服务器不存在"),
    /**
     * 资源不存在
     */
    RESOURCE_NOT_FOUND("C0404S0000", "资源不存在"),
    /**
     * 用户不存在
     */
    USER_NOT_FOUND("D0404S0001", "用户不存在"),
    /**
     * 数据不存在
     */
    DATA_NOT_FOUND("D0404S0002", "数据不存在"),
    /**
     * 文件不存在
     */
    FILE_NOT_FOUND("D0404S0003", "文件不存在"),

    // ==================== 数据错误 (D) ====================

    /**
     * 数据已存在
     */
    DATA_ALREADY_EXISTS("D0409S0001", "数据已存在"),
    /**
     * 用户名已存在
     */
    USERNAME_ALREADY_EXISTS("D0409S0002", "用户名已存在"),
    /**
     * 数据冲突
     */
    DATA_CONFLICT("D0409S0003", "数据冲突"),
    /**
     * 数据已被修改
     */
    DATA_MODIFIED("D0409S0004", "数据已被修改, 请刷新后重试"),
    /**
     * 数据引用关联, 无法删除
     */
    DATA_REFERENCED("D0409S0005", "数据被引用, 无法删除"),

    // ==================== 业务错误 (B) ====================

    /**
     * 操作过于频繁
     */
    OPERATION_TOO_FREQUENT("B0429S0001", "操作过于频繁, 请稍后重试"),
    /**
     * 功能未开启
     */
    FEATURE_NOT_ENABLED("B0503S0001", "功能未开启"),
    /**
     * 功能维护中
     */
    FEATURE_UNDER_MAINTENANCE("B0503S0002", "功能维护中"),
    /**
     * 业务处理失败
     */
    BUSINESS_ERROR("B0500S0001", "业务处理失败"),
    /**
     * 操作不允许
     */
    OPERATION_NOT_ALLOWED("B0403S0001", "操作不允许"),
    /**
     * 库存不足
     */
    STOCK_NOT_ENOUGH("B0422S0001", "库存不足"),
    /**
     * 余额不足
     */
    BALANCE_NOT_ENOUGH("B0422S0002", "余额不足"),

    // ==================== 文件错误 (F) ====================

    /**
     * 文件上传失败
     */
    FILE_UPLOAD_FAILED("F0500S0001", "文件上传失败"),
    /**
     * 文件格式不支持
     */
    FILE_FORMAT_NOT_SUPPORTED("F0415S0001", "文件格式不支持"),
    /**
     * 文件超过大小限制
     */
    FILE_SIZE_EXCEEDED("F0413S0001", "文件超过大小限制"),
    /**
     * 文件下载失败
     */
    FILE_DOWNLOAD_FAILED("F0500S0002", "文件下载失败"),

    // ==================== 服务端错误 (S) ====================

    /**
     * 系统繁忙
     */
    SYSTEM_SERVER_BUSINESS_ERROR("S0503C0000", "系统繁忙"),
    /**
     * 执行超时
     */
    SYSTEM_EXECUTION_TIMEOUT("S0511C0000", "执行超时"),
    /**
     * 执行异常
     */
    SYSTEM_EXECUTION_ERROR("S0512C0000", "执行异常"),
    /**
     * 服务不可用
     */
    SERVICE_UNAVAILABLE("S0503C0001", "服务不可用"),
    /**
     * 数据库错误
     */
    DATABASE_ERROR("S0500C0001", "数据库错误"),
    /**
     * 缓存错误
     */
    CACHE_ERROR("S0500C0002", "缓存错误"),
    /**
     * 第三方服务错误
     */
    THIRD_PARTY_ERROR("S0502C0001", "第三方服务错误"),
    /**
     * 其它服务端错误
     */
    SYSTEM_SERVER_OTHER_ERROR("S9999C0000", "其它错误"),

    /**
     * 其它错误
     */
    OTHER("C0000S0000", "其它错误");

    /**
     * 状态码
     */
    private final String code;
    /**
     * 信息
     */
    private final String msg;

    /**
     * 根据 HTTP 状态码转换为系统状态码
     *
     * @param code HTTP 状态码
     * @return 系统状态码
     */
    public static ReturnCode valueOf(int code) {
        if (code == 0 || code == 200 || code == 201 || code == 204) {
            return OK;
        }
        if (code == 301 || code == 302) {
            return OK;
        }
        if (code >= 400 && code < 500) {
            return switch (code) {
                case 400 -> REQUEST_PARAM_ERROR;
                case 401 -> USER_NOT_LOGIN;
                case 403 -> RESOURCE_OAUTH_ERROR;
                case 404 -> RESOURCE_NOT_FOUND;
                case 405 -> OPERATION_NOT_ALLOWED;
                case 408 -> REMOTE_EXECUTION_TIMEOUT;
                case 409 -> DATA_CONFLICT;
                case 413 -> FILE_SIZE_EXCEEDED;
                case 415 -> FILE_FORMAT_NOT_SUPPORTED;
                case 422 -> REQUEST_PARAM_FORMAT_ERROR;
                case 429 -> REQUEST_RATE_LIMIT;
                default -> REQUEST_PARAM_ERROR;
            };
        }
        if (code >= 500 && code < 600) {
            return switch (code) {
                case 500 -> DATABASE_ERROR;
                case 501 -> SERVICE_UNAVAILABLE;
                case 502 -> THIRD_PARTY_ERROR;
                case 503 -> SYSTEM_SERVER_BUSINESS_ERROR;
                case 504 -> SYSTEM_EXECUTION_TIMEOUT;
                default -> SYSTEM_SERVER_BUSINESS_ERROR;
            };
        }
        return SYSTEM_SERVER_OTHER_ERROR;
    }

    /**
     * 根据系统 code 反查枚举
     *
     * @param code 编码
     * @return 状态码
     */
    public static ReturnCode fromCode(@Nullable String code) {
        if (code == null) {
            return SYSTEM_SERVER_OTHER_ERROR;
        }
        for (ReturnCode value : values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }
        return SYSTEM_SERVER_OTHER_ERROR;
    }

    /**
     * 根据异常类型自动推断系统状态码
     * <p>
     * 映射规则：
     * <ul>
     *   <li>项目自定义认证/权限异常 -> 认证/授权类</li>
     *   <li>超时/限制异常 -> 对应 HTTP 映射</li>
     *   <li>参数/格式异常 -> 客户端错误</li>
     *   <li>IO/网络异常 -> 远程执行超时</li>
     *   <li>其他 RuntimeException -> 系统繁忙</li>
     * </ul>
     *
     * @param throwable 异常
     * @return 状态码
     */
    public static ReturnCode fromThrowable(@Nullable Throwable throwable) {
        if (null == throwable) {
            return SYSTEM_SERVER_OTHER_ERROR;
        }

        String className = throwable.getClass().getName();

        // 项目自定义异常 - 认证/授权类
        if (isClass(className, "AuthenticationException")) {
            return USER_NOT_LOGIN;
        }
        if (isClass(className, "NoPermissionException")) {
            return ACCESS_DENIED;
        }

        // 项目自定义异常 - 其他
        if (isClass(className, "RuntimeTimeoutException")) {
            return SYSTEM_EXECUTION_TIMEOUT;
        }
        if (isClass(className, "RuntimeLimitException")) {
            return REQUEST_RATE_LIMIT;
        }
        if (isClass(className, "NotSupportedException")) {
            return OPERATION_NOT_ALLOWED;
        }
        if (isClass(className, "BeanNotFoundException") || isClass(className, "ResourceNotFoundException")) {
            return RESOURCE_NOT_FOUND;
        }
        if (isClass(className, "BeanTypeMismatchException")) {
            return REQUEST_PARAM_FORMAT_ERROR;
        }
        if (isClass(className, "PoolTimeoutException")) {
            return SYSTEM_EXECUTION_TIMEOUT;
        }
        if (isClass(className, "PoolExhaustedException")) {
            return SERVICE_UNAVAILABLE;
        }

        // Java 标准异常
        if (throwable instanceof IllegalArgumentException || throwable instanceof NullPointerException) {
            return REQUEST_PARAM_ERROR;
        }
        if (throwable instanceof NumberFormatException) {
            return REQUEST_PARAM_FORMAT_ERROR;
        }
        if (throwable instanceof IndexOutOfBoundsException) {
            return REQUEST_PARAM_OUT_OF_RANGE;
        }
        if (throwable instanceof ClassCastException) {
            return REQUEST_PARAM_FORMAT_ERROR;
        }
        if (throwable instanceof UnsupportedOperationException) {
            return OPERATION_NOT_ALLOWED;
        }
        if (throwable instanceof SecurityException) {
            return ACCESS_DENIED;
        }
        if (throwable instanceof java.net.SocketTimeoutException) {
            return SYSTEM_EXECUTION_TIMEOUT;
        }
        if (throwable instanceof java.net.SocketException) {
            return REMOTE_EXECUTION_TIMEOUT;
        }
        if (throwable instanceof java.io.IOException) {
            return REMOTE_EXECUTION_TIMEOUT;
        }

        return SYSTEM_SERVER_OTHER_ERROR;
    }

    /**
     * 判断类名是否匹配
     *
     * @param className 类名
     * @param simpleName 简单类名
     * @return 是否匹配
     */
    private static boolean isClass(@Nullable String className, String simpleName) {
        if (className == null) {
            return false;
        }
        return className.endsWith("." + simpleName) || className.equals(simpleName);
    }
}
