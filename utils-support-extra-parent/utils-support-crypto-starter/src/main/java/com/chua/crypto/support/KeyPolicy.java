package com.chua.crypto.support;

/**
 * 密钥策略
 *
 * <p>决定主密钥保护密钥(KEK)的派生来源，即"谁来解锁密钥载体"。
 * 无论何种策略，落盘/落盘外载体的主密钥均为密文形态（隐私存储），明文仅存在于进程内存。
 *
 * <ul>
 *   <li>{@link #CUSTOM} — 自定义：由使用者提供的口令派生 KEK，密钥文件可随人迁移，口令丢失则数据不可恢复</li>
 *   <li>{@link #SERVER_BOUND} — 绑定服务器：由服务器硬件指纹(主机名/MAC/CPU/OS 等)派生 KEK，
 *       密钥文件离开本机无法解密；可通过 {@code serverId} 固定指纹以支持容灾迁移</li>
 * </ul>
 *
 * @author CH
 * @since 2026-08-26
 */
public enum KeyPolicy {

    /**
     * 自定义密钥：KEK 由使用者口令(secret)经 PBKDF2 派生
     */
    CUSTOM,

    /**
     * 绑定服务器：KEK 由服务器指纹经 PBKDF2 派生（可选叠加口令作为 pepper）
     */
    SERVER_BOUND
}
