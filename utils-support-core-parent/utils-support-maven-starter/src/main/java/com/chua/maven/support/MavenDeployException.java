package com.chua.maven.support;

/**
 * Maven 部署异常。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MavenDeployException extends RuntimeException {

    /**
     * 创建 MavenDeployException 实例
     * @param message message
     */
    public MavenDeployException(String message) {
        super(message);
    }

    /**
     * 创建 MavenDeployException 实例
     * @param message message
     * @param Throwable Throwable
     */
    public MavenDeployException(String message, Throwable cause) {
        super(message, cause);
    }
}