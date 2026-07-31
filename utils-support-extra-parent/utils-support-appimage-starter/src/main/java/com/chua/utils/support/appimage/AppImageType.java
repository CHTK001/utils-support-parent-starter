package com.chua.utils.support.appimage;

/**
 * AppImage 打包类型
 *
 * @author CH
 */
public enum AppImageType {

    /*
     * Docker 镜像类型
     */
    DOCKER_IMAGE,

    /*
     * Docker Compose 编排类型
     */
    DOCKER_COMPOSE,

    /*
     * 操作系统环境变量类型
     */
    OS_ENVIRONMENT,

    /*
     * 单文件可执行类型
     */
    SINGLE_FILE,

    /*
     * Java 应用类型
     */
    JAVA_APPLICATION
}