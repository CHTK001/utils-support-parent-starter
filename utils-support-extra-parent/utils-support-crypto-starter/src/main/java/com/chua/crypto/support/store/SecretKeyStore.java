package com.chua.crypto.support.store;

import com.chua.crypto.support.CryptoSetting;
import com.chua.crypto.support.key.SecretKeyMaterial;

/**
* 密钥载体 SPI（服务 提供者 接口）
*
* <p>抽象主密钥的持久化载体，负责密文的写入、加载、存在性判断与销毁。
* 载体内保存的永远是"经 KEK 封装后的主密钥密文"，明文主密钥仅存在于进程内存（隐私存储）。
*
* <p>内置实现：
* <ul>
*   <li>{@link FileSecretKeyStore}（别名 {@code file}）— 本地密钥文件</li>
* </ul>
*
* <p>实现通过 {@code META-INF/extensions/com.chua.crypto.support.store.SecretKeyStore} 注册，
* 经 {@code ServiceProvider.of(SecretKeyStore.class).getExtension(alias)} 发现。
*
* @author CH
* @since 2026-08-26
 */
public interface SecretKeyStore extends AutoCloseable {

    /**
    * 加载主密钥材料。
    *
    * <p>实现须校验载体完整性(HMAC)并按密钥策略解封；当生命周期为
    * {@code ONE_TIME} 且载体可擦除时，加载成功后应立即销毁落盘副本。
    *
    * @param setting 加密配置
    * @return 密钥材料；载体不存在时返回 空
    */
    SecretKeyMaterial load(CryptoSetting setting);

    /**
    * 写入主密钥材料（以当前策略派生的 KEK 封装后落盘）
    *
    * @param material 密钥材料
    * @param setting  加密配置
    */
    void save(SecretKeyMaterial material, CryptoSetting setting);

    /**
    * 载体是否已存在
    *
    * @param setting 加密配置
    * @return true 表示载体已存在
    */
    boolean exists(CryptoSetting setting);

    /**
    * 销毁载体（安全擦除后删除）
    *
    * @param setting 加密配置
    */
    void destroy(CryptoSetting setting);

    /**
    * 释放资源，默认空实现
    */
    @Override
    default void close() {
    }
}
