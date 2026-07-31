package com.chua.maven.support;

/**
 * Maven 部署异常。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MavenDeployException extends RuntimeException {

    public MavenDeployException(String message) {
        super(message);
    }

    public MavenDeployException(String message, Throwable cause) {
        super(message, cause);
    }
}