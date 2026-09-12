package com.chua.crypto.support.spring;

import com.chua.crypto.support.CryptoSetting;
import com.chua.crypto.support.KeyLifecycle;
import com.chua.crypto.support.KeyPolicy;
import com.chua.crypto.support.KeyStoreType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
* 系统加密配置属性（chua.加密货币.*）
*
* <p>配置示例：
* <pre>{@code
* chua:
*   crypto:
*     enabled: true                  # 是否启用
*     key-policy: SERVER_BOUND       # 密钥策略：CUSTOM / SERVER_BOUND
*     lifecycle: PERSISTENT          # 生命周期：ONE_TIME / PERSISTENT
*     store-type: FILE               # 载体：FILE / MEMORY
*     key-file: security/master.key  # 密钥文件路径
*     secret: ${CHUA_CRYPTO_SECRET}  # 自定义口令（建议环境变量注入）
*     encrypt-config-files: true     # 配置文件是否一起解密装载
*     config-files: application.yml, application-prod.yml
* }</pre> * 配置-文件: application.yml, application-prod.yml
* }</pre>
*
* @author CH
* @since 2026-08-26
 */
@Data
@ConfigurationProperties(prefix = "chua.crypto")
public class CryptoProperties {

    /**
    * 是否启用系统加密模块
     */
    private boolean enabled = true;

    /**
    * 密钥策略：自定义/绑定服务器
     */
    private KeyPolicy keyPolicy = KeyPolicy.SERVER_BOUND;

    /**
    * 密钥生命周期：一次性读取即销毁/持久
     */
    private KeyLifecycle lifecycle = KeyLifecycle.PERSISTENT;

    /**
    * 密钥载体类型：密钥文件/内存
     */
    private KeyStoreType storeType = KeyStoreType.FILE;

    /**
    * 密钥文件路径（支持 fatjar 相对路径解析）
     */
    private String keyFile;

    /**
    * 自定义口令（建议经环境变量或启动参数注入，勿提交到代码库）
     */
    private char[] secret;

    /**
    * 固定服务器指纹标识（容灾迁移场景）
     */
    private String serverId;

    /**
    * 数据加密算法
     */
    private String algorithm = "AES/GCM/NoPadding";

    /**
    * 配置文件是否随系统一起处理（启动期自动解密已加密的配置文件）
     */
    private boolean encryptConfigFiles = false;

    /**
    * 参与整体加解密的配置文件列表
     */
    private List<String> configFiles = new ArrayList<>();

    /**
    * 配置文件加密时是否保留明文备份(*.bak)
     */
    private boolean configBackup = true;

    /**
    * 转换为通用加密配置
    *
    * @return 加密配置
     */
    public CryptoSetting toSetting() {
        CryptoSetting setting = new CryptoSetting();
        setting.setKeyPolicy(keyPolicy);
        setting.setLifecycle(lifecycle);
        setting.setStoreType(storeType);
        setting.setKeyFile(keyFile);
        setting.setServerId(serverId);
        setting.setAlgorithm(algorithm);
        setting.setEncryptConfigFiles(encryptConfigFiles);
        setting.setConfigBackup(configBackup);
        setting.getConfigFiles().clear();
        setting.getConfigFiles().addAll(configFiles);
        if (secret != null) {
            setting.setSecret(Arrays.copyOf(secret, secret.length));
        }
        return setting;
    }
}
