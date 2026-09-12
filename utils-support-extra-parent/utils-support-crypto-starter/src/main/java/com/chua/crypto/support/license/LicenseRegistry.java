package com.chua.crypto.support.license;

import java.util.Map;

/**
* 校验服务器注册表抽象
*
* <p>生产部署可实现本接口接入数据库/配置中心等后端，
* {@link LicenseServerFilter} 仅依赖本接口。
*
* <p>语义：
* <ul>
*   <li>{@link #lookup} — 指纹已注册返回私钥封装块，未注册返回 null</li>
*   <li>{@link #register} — 签发/更新（幂等）</li>
*   <li>{@link #revoke} — 吊销后 {@link #lookup} 返回 null</li>
* </ul>
*
* @author CH
* @since 2026-08-26
 */
public interface LicenseRegistry {

    /**
    * 查询指纹注册的私钥封装块
    *
    * @param fingerprint 指纹十六进制串
    * @return 封装块字节；未注册返回 空
     */
    byte[] lookup(String fingerprint);

    /**
    * 注册/更新指纹对应的私钥封装块（幂等）
    *
    * @param fingerprint 指纹十六进制串
    * @param blob        私钥封装块
     */
    void register(String fingerprint, byte[] blob);

    /**
    * 吊销指纹
    *
    * @param fingerprint 指纹
    * @return true 表示存在且已移除
     */
    boolean revoke(String fingerprint);

    /**
    * 指纹是否已注册
    *
    * @param fingerprint 指纹
    * @return true 表示已注册
     */
    boolean contains(String fingerprint);

    /**
    * 全部注册项快照（管理端展示用）
    *
    * @return 只读映射：指纹 -> 基础64(封装块)
     */
    Map<String, String> snapshot();
}
