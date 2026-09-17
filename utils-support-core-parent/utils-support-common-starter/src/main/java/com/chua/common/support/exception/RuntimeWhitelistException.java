package com.chua.common.support.exception;


/**
* 运行时白名单异常。
* <p>
* 当业务逻辑中检测到操作对象不在允许的白名单范围内时，抛出此异常。
* 通常用于权限校验、数据过滤等场景，提示当前请求的资源或用户未被授权访问。
*
* @author CH
* @since 4.0.0.42
 */
public class RuntimeWhitelistException extends RuntimeException {

    /**
    * 构造一个带有详细消息的运行时白名单异常。
    *
    * @param message 描述异常原因的详细信息
    */
    public RuntimeWhitelistException(String message) {
        super(message);
    }

    /**
    * 构造一个带有详细消息和 cause（根本原因）的运行时白名单异常。
    *
    * @param message 描述异常原因的详细信息
    * @param cause   导致此异常的底层原因
    */
    public RuntimeWhitelistException(String message, Throwable cause) {
        super(message, cause);
    }
}
