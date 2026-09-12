package com.chua.crypto.support;

/**
* 密钥生命周期
*
* <p>约束密钥载体（密钥文件等持久化形态）的存续方式：
*
* <ul>
*   <li>{@link #ONE_TIME} — 一次性读取即销毁：载体首次读取成功后立即擦除并删除落盘副本，
*       主密钥仅存活于本次进程内存；进程退出后历史密文不可再解，适用于临时会话、敏感中间数据</li>
*   <li>{@link #PERSISTENT} — 持久：载体长期保留，可反复加载解密，适用于长期数据加密</li>
* </ul>
*
*
* @author CH
* @since 2026-08-26
 */
public enum KeyLifecycle {

    /**
    * 一次性读取即销毁（仅对可擦除载体生效，如密钥文件）
     */
    ONE_TIME,

    /**
    * 持久化
     */
    PERSISTENT
}
