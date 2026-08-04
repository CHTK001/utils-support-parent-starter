package com.chua.common.support.lang.code;

import static com.chua.common.support.lang.code.ReturnCode.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * 结果状态码接口
 * <p>
 * 提供 HTTP 状态码到系统 {@link ReturnCode} 的规范映射,
 * 映射规则覆盖 2xx/3xx/4xx/5xx 全系列标准状态码。
 *
 * @author CH
 */
@NullMarked
public interface ResultCode {

    // ==================== HTTP 常量 ====================
    /**
     * HTTP 200 状态码
     */
    int HTTP_200 = 200;
    /**
     * HTTP 201 状态码
     */
    int HTTP_201 = 201;
    /**
     * HTTP 204 状态码
     */
    int HTTP_204 = 204;
    /**
     * HTTP 301 状态码
     */
    int HTTP_301 = 301;
    /**
     * HTTP 302 状态码
     */
    int HTTP_302 = 302;
    /**
     * HTTP 300 状态码
     */
    int HTTP_300 = 300;
    /**
     * HTTP 400 状态码
     */
    int HTTP_400 = 400;
    /**
     * HTTP 401 状态码
     */
    int HTTP_401 = 401;
    /**
     * HTTP 403 状态码
     */
    int HTTP_403 = 403;
    /**
     * HTTP 404 状态码
     */
    int HTTP_404 = 404;
    /**
     * HTTP 405 状态码
     */
    int HTTP_405 = 405;
    /**
     * HTTP 408 状态码
     */
    int HTTP_408 = 408;
    /**
     * HTTP 409 状态码
     */
    int HTTP_409 = 409;
    /**
     * HTTP 413 状态码
     */
    int HTTP_413 = 413;
    /**
     * HTTP 415 状态码
     */
    int HTTP_415 = 415;
    /**
     * HTTP 422 状态码
     */
    int HTTP_422 = 422;
    /**
     * HTTP 429 状态码
     */
    int HTTP_429 = 429;
    /**
     * HTTP 500 状态码
     */
    int HTTP_500 = 500;
    /**
     * HTTP 501 状态码
     */
    int HTTP_501 = 501;
    /**
     * HTTP 502 状态码
     */
    int HTTP_502 = 502;
    /**
     * HTTP 503 状态码
     */
    int HTTP_503 = 503;
    /**
     * HTTP 504 状态码
     */
    int HTTP_504 = 504;
    /**
     * HTTP 600 状态码
     */
    int HTTP_600 = 600;

    /**
     * 转化编码
     *
     * @param status 编码
     * @return 结果
     */
    static String transferForHttpCodeStatus(@Nullable Integer status) {
        return transferForHttpCode(status).getMsg();
    }

    /**
     * 转化编码
     *
     * @param status 编码
     * @return 结果
     */
    static ResultCode transferForHttpCode(@Nullable Integer status) {
        if (null == status) {
            return SYSTEM_SERVER_OTHER_ERROR;
        }

        // 2xx 成功
        if (status >= HTTP_200 && status < HTTP_300) {
            return OK;
        }

        // 3xx 重定向
        if (status >= HTTP_301 && status < HTTP_400) {
            return OK;
        }

        // 4xx 客户端错误
        if (status >= HTTP_400 && status < HTTP_500) {
            switch (status) {
                case HTTP_400:
                    return REQUEST_PARAM_ERROR;
                case HTTP_401:
                    return USER_NOT_LOGIN;
                case HTTP_403:
                    return RESOURCE_OAUTH_ERROR;
                case HTTP_404:
                    return RESOURCE_NOT_FOUND;
                case HTTP_405:
                    return OPERATION_NOT_ALLOWED;
                case HTTP_408:
                    return REMOTE_EXECUTION_TIMEOUT;
                case HTTP_409:
                    return DATA_CONFLICT;
                case HTTP_413:
                    return FILE_SIZE_EXCEEDED;
                case HTTP_415:
                    return FILE_FORMAT_NOT_SUPPORTED;
                case HTTP_422:
                    return REQUEST_PARAM_FORMAT_ERROR;
                case HTTP_429:
                    return REQUEST_RATE_LIMIT;
                default:
                    return REQUEST_PARAM_ERROR;
            }
        }

        // 5xx 服务端错误
        if (status >= HTTP_500 && status < HTTP_600) {
            switch (status) {
                case HTTP_500:
                    return DATABASE_ERROR;
                case HTTP_501:
                    return SERVICE_UNAVAILABLE;
                case HTTP_502:
                    return THIRD_PARTY_ERROR;
                case HTTP_503:
                    return SYSTEM_SERVER_BUSINESS_ERROR;
                case HTTP_504:
                    return SYSTEM_EXECUTION_TIMEOUT;
                default:
                    return SYSTEM_SERVER_BUSINESS_ERROR;
            }
        }

        return SYSTEM_SERVER_OTHER_ERROR;
    }

    /**
     * 状态码
     *
     * @return 状态码
     */
    String getCode();

    /**
     * 信息
     *
     * @return 信息
     */
    String getMsg();

    /**
     * 是否存在异常
     *
     * @return 是否存在异常
     */
    default boolean hasError() {
        return this != OK && this != SUCCESS;
    }
}
