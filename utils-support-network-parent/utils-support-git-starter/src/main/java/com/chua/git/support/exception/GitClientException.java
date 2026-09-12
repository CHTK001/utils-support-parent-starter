package com.chua.git.support.exception;

/**
* Git 客户端统一异常。
*
* <p>该类继承自 {@link RuntimeException}，用于封装所有 Git 操作中可能出现的错误，
* 包括但不限于：仓库打开失败、拉取失败、推送被拒绝、克隆网络超时、diff 计算异常等。</p>
*
* <p>调用方无需在方法签名中声明 checked exception，可以在业务层统一捕获此异常
* 并根据 消息 和 cause 定位具体问题。</p>
*
* <pre>{@code
* try {
*     client.open().pull().execute();
* } catch (GitClientException e) {
*     log.error("Git 操作失败: {}", e.getMessage(), e);
* }
* }</pre>
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public class GitClientException extends RuntimeException {

    /**
    * 使用错误消息构造异常。
    *
    * @param message 错误描述
     */
    public GitClientException(String message) {
        super(message);
    }

    /**
    * 使用错误消息及原始异常构造异常。
    *
    * @param message 错误描述
    * @param cause   原始异常，便于调用方通过 {@link #getCause()} 获取 jgit 等底层异常
     */
    public GitClientException(String message, Throwable cause) {
        super(message, cause);
    }
}