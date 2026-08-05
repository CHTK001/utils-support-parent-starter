package com.chua.common.support.lang.cmd;

/**
 * 可运行工件的运行时类型枚举。
 *
 * <p>用于标识 {@link com.chua.common.support.lang.cmd.Cmd} 或运行时管理系统中
 * 可启动、停止、管理的工件类型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum RuntimeType {

    /**
     * 可执行 JAR 包（java -jar xxx.jar）
     */
    JAR,

    /**
     * AppImage 格式的 Linux 应用
     */
    APPIMAGE,

    /**
     * 原生可执行二进制文件（exe、bin、elf 等）
     */
    NATIVE,

    /**
     * Node.js 包（npm start / node xxx.js）
     */
    NPM,

    /**
     * Python 脚本（python xxx.py）
     */
    PYTHON,

    /**
     * 通用脚本（sh、bat、ps1 等）
     */
    SCRIPT,

    /**
     * Docker 容器（docker run）
     */
    DOCKER,

    /**
     * 未知/自动检测类型
     */
    UNKNOWN
}