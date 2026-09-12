package com.chua.crypto.support;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
* 系统加密配置
*
* <p>承载 {@link Crypto} 链式 API 的全部设置项，亦可由
* {@code com.chua.crypto.support.spring.CryptoProperties}（chua.crypto.* 配置）直接转换而来。
*
* <p>默认值：
* <ul>
*   <li>密钥策略 {@link KeyPolicy#SERVER_BOUND}（绑定服务器）</li>
*   <li>生命周期 {@link KeyLifecycle#PERSISTENT}（持久）</li>
*   <li>载体 {@link KeyStoreType#FILE}（密钥文件，路径缺省为 {user.home}/.chua/crypto/master.key）</li>
*   <li>数据算法 AES/GCM/NoPadding（256 位主密钥，每次加密随机 IV）</li>
*   <li>配置文件不随密钥一起加密</li>
* </ul>
*
* @author CH
* @since 2026-08-26
 */
@Getter
@Setter
public class CryptoSetting {

    /**
    * 密钥策略，默认绑定服务器
     */
    private KeyPolicy keyPolicy = KeyPolicy.SERVER_BOUND;

    /**
    * 密钥生命周期，默认持久
     */
    private KeyLifecycle lifecycle = KeyLifecycle.PERSISTENT;

    /**
    * 密钥载体类型，默认密钥文件
     */
    private KeyStoreType storeType = KeyStoreType.FILE;

    /**
    * 密钥文件路径（存储类型=文件 时生效），支持相对路径（自动按 工作目录 → fatjar 所在目录 → 用户目录 顺序解析）
     */
    private String keyFile;

    /**
    * 自定义口令（习俗 策略必填；服务端_BOUND 策略可选，作为 pepper 叠加），使用后可安全擦除
     */
    private char[] secret;

    /**
    * 固定服务器指纹（服务端_BOUND 策略下用于容灾迁移；缺省自动采集本机指纹，
    * 亦可通过环境变量 CHUA_加密货币_服务端_标识 或系统属性 chua.加密货币.服务端-标识 指定）
     */
    private String serverId;

    /**
    * 数据加密算法，默认 AES/GCM/nopadding
     */
    private String algorithm = "AES/GCM/NoPadding";

    /**
    * 配置文件是否随系统一起加密（true 时 encrypt配置文件/decrypt配置文件 可批量处理 配置文件）
     */
    private boolean encryptConfigFiles = false;

    /**
    * 参与整体加解密的配置文件列表（如 application.yml、application-prod.yml）
     */
    private List<String> configFiles = new ArrayList<>();

    /**
    * 配置文件加密时是否保留明文备份(*.bak)，默认保留以便回滚
     */
    private boolean configBackup = true;

    /**
    * 追加配置文件
    *
    * @param files 配置文件路径（支持多个）
    * @return 当前设置
     */
    public CryptoSetting addConfigFiles(String... files) {
        if (files != null) {
            configFiles.addAll(Arrays.asList(files));
        }
        return this;
    }

    /**
    * 释放口令内存（将口令数组清零），建议在构建完成后调用
     */
    public void wipeSecret() {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }
}
