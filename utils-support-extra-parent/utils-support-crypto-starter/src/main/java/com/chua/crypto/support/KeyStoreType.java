package com.chua.crypto.support;

/**
 * 密钥载体类型
 *
 * <ul>
 *   <li>{@link #FILE} — 密钥文件：本地持久化文件（默认），支持一次性/持久生命周期</li>
 *   <li>{@link #MEMORY} — 纯内存：不落盘，每次构建生成随机主密钥，进程退出即失效</li>
 *   <li>{@link #DONGLE} — U 盘加密狗：密钥封装于可移动介质文件中，支持{@link DongleSecretKeyStore 生成与解析}</li>
 * </ul>
 *
 * @author CH
 * @since 2026-08-26
 */
public enum KeyStoreType {

    /**
     * 密钥文件载体
     */
    FILE,

    /**
     * 纯内存载体
     */
    MEMORY,

    /**
     * U 盘加密狗载体
     */
    DONGLE
}
