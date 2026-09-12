package com.chua.maven.support;

/**
* Maven 部署异常。
*
* @author CH
* @since 4.0.0.42
 */
public class MavenDeployException extends RuntimeException {

    /**
    * 创建 mavendeploy异常 实例
    * @param message 消息
     */
    public MavenDeployException(String message) {
        super(message);
    }

    /**
    * 创建 mavendeploy异常 实例
    * @param message 消息
    * @param cause Throwable
    * @param cause cause
     */
    public MavenDeployException(String message, Throwable cause) {
        super(message, cause);
    }
}