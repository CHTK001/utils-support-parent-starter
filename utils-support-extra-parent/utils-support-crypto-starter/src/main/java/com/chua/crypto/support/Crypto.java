package com.chua.crypto.support;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.crypto.support.codec.DataCipher;
import com.chua.crypto.support.config.ConfigFileCipher;
import com.chua.crypto.support.key.SecretKeyMaterial;
import com.chua.crypto.support.store.KeyFileResolver;
import com.chua.crypto.support.store.SecretKeyStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
* 系统加密门面（链式 API）
*
* <p>一站式完成：密钥策略设置（自定义/绑定服务器）、生命周期设置（一次性读取即销毁/持久）、
* 密钥载体选择（密钥文件/内存）、数据加解密、配置文件整体或单值加密。
* 同时适配普通 Java、springboot、fatjar 等运行形态（相对路径自动按 工作目录 → Jar 目录 → 用户目录 解析）。
*
* <h2>使用示例</h2>
* <pre>{@code
* // 1. 绑定服务器 + 持久密钥文件（默认策略）
* Crypto crypto = Crypto.create()
*         .keyPolicy(KeyPolicy.SERVER_BOUND)
*         .lifecycle(KeyLifecycle.PERSISTENT)
*         .keyFile("security/master.key")
*         .build();
*
* // 2. 自定义口令 + 一次性读取即销毁
* Crypto ephemeral = Crypto.create()
*         .keyPolicy(KeyPolicy.CUSTOM)
*         .secret("my-passphrase".toCharArray())
*         .lifecycle(KeyLifecycle.ONE_TIME)
*         .keyFile("security/once.key")
*         .build();
*
* // 4. 配置文件随系统一起加密
* Crypto.create()
*         .encryptConfig(true)
*         .configFile("application.yml", "application-prod.yml")
*         .build()
*         .encryptConfigFiles();
*
* String cipherText = crypto.encryptToString("hello");
* String plainText  = crypto.decryptToString(cipherText);
* }</pre>
*         .build()
*         .encryptConfigFiles();
*
* String cipherText = crypto.encryptToString("hello");
* String plainText  = crypto.decryptToString(cipherText);
* }</pre>
*
* <p>实例实现 {@link AutoCloseable}：{@link #close()} 擦除内存密钥；持久载体不受影响，
* 如需彻底作废落盘密钥请调用 {@link #destroyCarrier()}。
*
* @author CH
* @since 2026-08-26
 */
@Slf4j
public class Crypto implements AutoCloseable {

    /**
    * 内部配置
     */
    private final CryptoSetting setting = new CryptoSetting();

    /**
    * 主密钥材料（初始化后可用）
     */
    private volatile SecretKeyMaterial material;

    /**
    * 是否已初始化
     */
    private volatile boolean initialized;

    /**
    * 私有构造，统一从 {@link #create()} / {@link #from(CryptoSetting)} 进入
     */
    private Crypto() {
    }

    /**
    * 创建链式构建器入口
    *
    * @return 未初始化的加密门面
     */
    public static Crypto create() {
        return new Crypto();
    }

    /**
    * 从既有配置创建（SpringBoot 属性绑定场景）
    *
    * @param setting 加密配置
    * @return 未初始化的加密门面
     */
    public static Crypto from(CryptoSetting setting) {
        Crypto crypto = new Crypto();
        crypto.apply(setting);
        return crypto;
    }

    /**
    * 应用既有配置（覆盖当前全部设置）
    *
    * @param source 来源配置
    * @return 当前对象
     */
    public Crypto apply(CryptoSetting source) {
        setting.setKeyPolicy(source.getKeyPolicy());
        setting.setLifecycle(source.getLifecycle());
        setting.setStoreType(source.getStoreType());
        setting.setKeyFile(source.getKeyFile());
        setting.setServerId(source.getServerId());
        setting.setAlgorithm(source.getAlgorithm());
        setting.setEncryptConfigFiles(source.isEncryptConfigFiles());
        setting.setConfigBackup(source.isConfigBackup());
        setting.getConfigFiles().clear();
        setting.getConfigFiles().addAll(source.getConfigFiles());
        if (source.getSecret() != null) {
            setting.setSecret(Arrays.copyOf(source.getSecret(), source.getSecret().length));
        }
        return this;
    }

    // ------------------------------------------------------------------
    // 链式配置
    // ------------------------------------------------------------------

    /**
    * 设置密钥策略
    *
    * @param keyPolicy 自定义(习俗)/绑定服务器(服务端_BOUND)
    * @return 当前对象
     */
    public Crypto keyPolicy(KeyPolicy keyPolicy) {
        setting.setKeyPolicy(keyPolicy);
        return this;
    }

    /**
    * 设置密钥生命周期
    *
    * @param lifecycle 一次性读取即销毁(ONE_时间)/持久(PERSISTENT)
    * @return 当前对象
     */
    public Crypto lifecycle(KeyLifecycle lifecycle) {
        setting.setLifecycle(lifecycle);
        return this;
    }

    /**
    * 设置密钥文件路径并切换为文件载体
    *
    * @param keyFile 路径（绝对或相对）
    * @return 当前对象
     */
    public Crypto keyFile(String keyFile) {
        setting.setStoreType(KeyStoreType.FILE);
        setting.setKeyFile(keyFile);
        return this;
    }

    /**
    * 设置自定义口令（习俗 策略必填；服务端_BOUND 策略可作为 pepper 叠加）
    *
    * @param secret 口令字符数组
    * @return 当前对象
     */
    public Crypto secret(char[] secret) {
        setting.setSecret(secret != null ? Arrays.copyOf(secret, secret.length) : null);
        return this;
    }

    /**
    * 设置自定义口令
    *
    * @param secret 口令字符串
    * @return 当前对象
     */
    public Crypto secret(CharSequence secret) {
        return secret(secret == null ? null : secret.toString().toCharArray());
    }

    /**
    * 固定服务器指纹标识（服务端_BOUND 策略容灾迁移场景；缺省自动采集本机指纹）
    *
    * @param serverId 稳定标识
    * @return 当前对象
     */
    public Crypto serverId(String serverId) {
        setting.setServerId(serverId);
        return this;
    }

    /**
    * 设置数据加密算法（缺省 AES/GCM/nopadding）
    *
    * @param algorithm JCE 变换名
    * @return 当前对象
     */
    public Crypto algorithm(String algorithm) {
        setting.setAlgorithm(algorithm);
        return this;
    }

    /**
    * 设置纯内存载体：不落盘，每次构建生成随机主密钥，进程退出即失效
    *
    * @return 当前对象
     */
    public Crypto memory() {
        setting.setStoreType(KeyStoreType.MEMORY);
        return this;
    }

    /**
    * 设置配置文件是否随系统一起加密
    *
    * @param encryptConfig true 表示参与批量加密
    * @return 当前对象
     */
    public Crypto encryptConfig(boolean encryptConfig) {
        setting.setEncryptConfigFiles(encryptConfig);
        return this;
    }

    /**
    * 追加参与整体加解密的配置文件（隐式开启 encrypt配置）
    *
    * @param files 配置文件路径（可多个）
    * @return 当前对象
     */
    public Crypto configFile(String... files) {
        setting.addConfigFiles(files);
        if (files != null && files.length > 0) {
            setting.setEncryptConfigFiles(true);
        }
        return this;
    }

    /**
    * 设置配置文件加密时是否保留明文备份(*.bak)
    *
    * @param backup true 表示保留备份
    * @return 当前对象
     */
    public Crypto configBackup(boolean backup) {
        setting.setConfigBackup(backup);
        return this;
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /**
    * 校验配置并加载/生成主密钥
    *
    * @return 已初始化的门面
     */
    public synchronized Crypto build() {
        return initializeInternal();
    }

    /**
    * {@link #build()} 别名，语义化初始化
    *
    * @return 已初始化的门面
     */
    public Crypto initialize() {
        return build();
    }

    /**
    * 初始化核心流程：
    * <ol>
    *   <li>参数校验（口令/载体/生命周期组合）</li>
    *   <li>MEMORY：直接生成随机主密钥；FILE：缺失时自动引导生成</li>
    *   <li>统一经载体 load 完成校验（ONE_TIME 在此步销毁落盘副本）</li>
    * </ol>
    * @return 初始化内部的结果
     */
    private synchronized Crypto initializeInternal() {
        if (initialized) {
            return this;
        }
        validate();

        if (setting.getStoreType() == KeyStoreType.MEMORY) {
            material = SecretKeyMaterial.generate();
        } else {
            SecretKeyStore store = resolveStore();
            try {
                if (!store.exists(setting)) {
                    ensureBootstrap(store);
                }
                SecretKeyMaterial loaded = store.load(setting);
                if (loaded == null) {
                    throw new CryptoException("主密钥加载失败：载体不可读");
                }
                material = loaded;
            } finally {
                store.close();
            }
        }
        initialized = true;
        log.info("系统加密模块已初始化: policy={}, lifecycle={}, store={}",
                setting.getKeyPolicy(), setting.getLifecycle(), setting.getStoreType());
        return this;
    }

    /**
    * 参数合法性校验
     */
    private void validate() {
        if (setting.getKeyPolicy() == KeyPolicy.CUSTOM
                && (setting.getSecret() == null || setting.getSecret().length == 0)) {
            throw new CryptoException("CUSTOM 密钥策略要求通过 secret(...) 提供口令");
        }
        if (setting.getConfigFiles() != null && !setting.getConfigFiles().isEmpty() && !setting.isEncryptConfigFiles()) {
            log.warn("已配置 config-files 但 encrypt-config=false，配置文件不会参与加密");
        }
    }

    /**
    * 载体缺失时的引导策略：密钥文件自动生成并写入
    *
    * @param store 密钥载体
     */
    private void ensureBootstrap(SecretKeyStore store) {
        SecretKeyMaterial fresh = SecretKeyMaterial.generate();
        store.save(fresh, setting);
        fresh.close();
    }

    /**
    * 经 SPI 解析密钥载体实现
    *
    * @return 载体实例
     */
    private SecretKeyStore resolveStore() {
        String alias = setting.getStoreType().name().toLowerCase();
        SecretKeyStore store = ServiceProvider.of(SecretKeyStore.class).getExtension(alias);
        if (store == null) {
            throw new CryptoException("未找到密钥载体实现: " + alias);
        }
        return store;
    }

    /**
    * 确保已初始化（懒初始化，保证链式 API 即用性）
     */
    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    initializeInternal();
                }
            }
        }
        if (material == null || material.isDestroyed()) {
            throw new CryptoException("主密钥不可用（已关闭或未初始化）");
        }
    }

    // ------------------------------------------------------------------
    // 数据加解密
    // ------------------------------------------------------------------

    /**
    * 加密字节数据
    *
    * @param plaintext 明文
    * @return 密文字节
     */
    public byte[] encrypt(byte[] plaintext) {
        ensureInitialized();
        return DataCipher.encrypt(material.copyKey(), plaintext);
    }

    /**
    * 解密字节数据
    *
    * @param ciphertext 密文
    * @return 明文
     */
    public byte[] decrypt(byte[] ciphertext) {
        ensureInitialized();
        return DataCipher.decrypt(material.copyKey(), ciphertext);
    }

    /**
    * 加密字符串（UTF-8），返回 基础64 密文
    *
    * @param plaintext 明文
    * @return Base64 密文
     */
    public String encryptToString(String plaintext) {
        ensureInitialized();
        return DataCipher.encryptToString(material.copyKey(), plaintext);
    }

    /**
    * 解密 基础64 密文
    *
    * @param ciphertext 基础64 密文
    * @return 明文
     */
    public String decryptToString(String ciphertext) {
        ensureInitialized();
        return DataCipher.decryptToString(material.copyKey(), ciphertext);
    }

    // ------------------------------------------------------------------
    // 配置文件加解密
    // ------------------------------------------------------------------

    /**
    * 批量加密已登记的配置文件（encrypt配置(true)+配置文件(...) 场景）
    *
    * @return 处理后的密文文件路径列表
     */
    public List<Path> encryptConfigFiles() {
        ensureInitialized();
        List<Path> results = new ArrayList<>();
        for (String name : setting.getConfigFiles()) {
            results.add(ConfigFileCipher.encryptFile(KeyFileResolver.resolve(name), this, setting.isConfigBackup()));
        }
        return results;
    }

    /**
    * 批量解密已登记的配置文件到同名 *.dec 明文文件
    *
    * @return 明文文件路径列表
     */
    public List<Path> decryptConfigFiles() {
        ensureInitialized();
        List<Path> results = new ArrayList<>();
        for (String name : setting.getConfigFiles()) {
            Path source = KeyFileResolver.resolve(name);
            Path target = source.resolveSibling(source.getFileName() + ".dec");
            results.add(ConfigFileCipher.decryptToFile(source, target, this));
        }
        return results;
    }

    /**
    * 整文件加密指定配置文件
    *
    * @param file 配置文件
    * @return 密文文件路径
     */
    public Path encryptConfigFile(Path file) {
        ensureInitialized();
        return ConfigFileCipher.encryptFile(file, this, setting.isConfigBackup());
    }

    /**
    * 解密整文件加密的配置内容
    *
    * @param file 密文配置文件
    * @return 明文内容
     */
    public String decryptConfigFile(Path file) {
        ensureInitialized();
        return ConfigFileCipher.decryptFile(file, this);
    }

    /**
    * 解密 ENC(...) 单值
    *
    * @param value 原始值
    * @return 明文值或原值
     */
    public String decryptValue(String value) {
        return ConfigFileCipher.decryptValue(value, this);
    }

    /**
    * 加密单值为 ENC(...) 形态
    *
    * @param value 明文值
    * @return ENC(Base64密文)
     */
    public String encryptValue(String value) {
        ensureInitialized();
        return ConfigFileCipher.encryptValue(value, this);
    }

    // ------------------------------------------------------------------
    // 状态与生命周期
    // ------------------------------------------------------------------

    /**
    * 是否已完成初始化
    *
    * @return true 表示主密钥已就绪
     */
    public boolean isInitialized() {
        return initialized && material != null && !material.isDestroyed();
    }

    /**
    * 获取只读配置快照
    *
    * @return 内部配置（请勿长期持有口令引用）
     */
    public CryptoSetting setting() {
        return setting;
    }

    /**
    * 获取当前主密钥材料（用于密钥备份/迁移等高级场景）
    *
    * @return 密钥材料
     */
    public SecretKeyMaterial material() {
        ensureInitialized();
        return material;
    }

    /**
    * 销毁持久载体上的密钥（安全擦除后删除），内存密钥同时失效。
    * 注意：此后历史密文将永久无法解密，请谨慎调用。
     */
    public synchronized void destroyCarrier() {
        if (setting.getStoreType() == KeyStoreType.MEMORY) {
            closeQuietly();
            return;
        }
        SecretKeyStore store = resolveStore();
        try {
            store.destroy(setting);
        } finally {
            store.close();
        }
        closeQuietly();
    }

    /**
    * 关闭门面：仅擦除内存中的主密钥，持久载体保持不变（可重新加载）
     */
    @Override
    public synchronized void close() {
        closeQuietly();
    }

    /**
    * 静默擦除内存密钥
     */
    private void closeQuietly() {
        SecretKeyMaterial current = material;
        if (current != null) {
            current.close();
        }
        material = null;
        initialized = false;
    }
}
