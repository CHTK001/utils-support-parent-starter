package com.chua.crypto.support.store;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.crypto.support.CryptoSetting;
import com.chua.crypto.support.KeyStoreType;

import java.nio.file.Path;

/**
 * 密钥文件载体（默认实现）
 *
 * <p>将封装后的主密钥持久化到本地文件（缺省 {user.home}/.chua/crypto/master.key，
 * 可通过链式 API {@code keyFile(...)} 或配置 {@code chua.crypto.key-file} 指定）。
 *
 * <p>特性：
 * <ul>
 *   <li>密文落盘 — 文件中仅存在 KEK 封装后的主密钥，无任何明文（隐私存储）</li>
 *   <li>生命周期支持一次性读取即销毁与持久两种</li>
 *   <li>FatJar 适配 — 相对路径按 工作目录 → Jar 目录 → 用户目录 顺序解析</li>
 * </ul>
 *
 * @author CH
 * @since 2026-08-26
 */
@Spi("file")
public class FileSecretKeyStore extends AbstractWrappedKeyStore {

    /**
      * 载体魔数：Chua 键 文件
     */
    private static final byte[] MAGIC = {'C', 'H', 'K', 'F'};

    /**
     * 解析载体路径
     *
     * @param setting 加密配置
     * @return 密钥文件绝对路径
     */
    @Override
    protected Path carrierPath(CryptoSetting setting) {
        return KeyFileResolver.resolve(setting.getKeyFile());
    }

    /**
     * 获取载体魔数
     *
     * @return CHKF
     */
    @Override
    protected byte[] magic() {
        return MAGIC.clone();
    }

    /**
     * 返回载体类型描述
     *
     * @return 类型名
     */
    @Override
    public String toString() {
        return KeyStoreType.FILE.name();
    }
}
