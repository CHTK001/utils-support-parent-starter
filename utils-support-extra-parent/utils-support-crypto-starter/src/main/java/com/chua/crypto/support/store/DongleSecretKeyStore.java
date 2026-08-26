package com.chua.crypto.support.store;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.crypto.support.CryptoException;
import com.chua.crypto.support.CryptoSetting;
import com.chua.crypto.support.KeyPolicy;
import com.chua.crypto.support.KeyStoreType;
import com.chua.crypto.support.key.SecretKeyMaterial;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * U 盘加密狗载体
 *
 * <p>将封装后的主密钥写入可移动介质（U 盘）上的密钥封装文件，实现"物理持有即授权"：
 * 密钥随 U 盘插拔控制应用可用性，适合离线授权、单机部署等场景。
 *
 * <p>支持<b>生成</b>与<b>解析</b>：
 * <ul>
 *   <li>生成 — {@link #generate(Path, char[])}：生成随机主密钥并以口令派生 KEK 封装后写入目标文件；
 *       亦可在应用运行中调用 {@link #exportTo(Path, CryptoSetting, char[])} 将当前主密钥导出为加密狗副本</li>
 *   <li>解析 — 作为载体加载：{@code Crypto.create().dongle("E:/license.dongle").secret(...).build()}</li>
 * </ul>
 *
 * <p>安全说明：加密狗为物理持久载体，不支持一次性生命周期；解析时需提供与生成时一致的口令，
 * 且口令错误/文件被篡改均会因 AES-GCM 认证或 HMAC 校验失败而拒绝。
 *
 * @author CH
 * @since 2026-08-26
 */
@Spi("dongle")
public class DongleSecretKeyStore extends AbstractWrappedKeyStore {

    /**
     * 载体魔数：Chua Key Dongle
     */
    private static final byte[] MAGIC = {'C', 'H', 'K', 'D'};

    /**
     * 默认加密狗文件名
     */
    public static final String DEFAULT_DONGLE_FILE = "chua-crypto.dongle";

    /**
     * 解析载体路径（必须已通过 {@code dongle(...)} 指定）
     *
     * @param setting 加密配置
     * @return 加密狗文件绝对路径
     */
    @Override
    protected Path carrierPath(CryptoSetting setting) {
        if (setting.getDonglePath() == null || setting.getDonglePath().isBlank()) {
            throw new CryptoException("DONGLE 载体要求指定加密狗路径(dongle)");
        }
        return Paths.get(setting.getDonglePath()).toAbsolutePath().normalize();
    }

    /**
     * 物理介质不允许被擦除销毁
     *
     * @return false
     */
    @Override
    protected boolean erasable() {
        return false;
    }

    /**
     * 获取载体魔数
     *
     * @return CHKD
     */
    @Override
    protected byte[] magic() {
        return MAGIC.clone();
    }

    /**
     * 生成全新加密狗：随机主密钥 + 口令(CUSTOM 策略)封装写入目标路径
     *
     * @param target     目标文件（通常位于 U 盘，如 E:/chua-crypto.dongle）
     * @param passphrase 保护口令（解析时须一致）
     */
    public static void generate(Path target, char[] passphrase) {
        CryptoSetting setting = new CryptoSetting();
        setting.setStoreType(KeyStoreType.DONGLE);
        setting.setKeyPolicy(KeyPolicy.CUSTOM);
        setting.setSecret(passphrase.clone());
        setting.setDonglePath(target.toAbsolutePath().toString());
        try {
            new DongleSecretKeyStore().save(SecretKeyMaterial.generate(), setting);
        } finally {
            setting.wipeSecret();
        }
    }

    /**
     * 将已有主密钥导出为加密狗副本（复制当前应用密钥到 U 盘）
     *
     * @param target     目标文件
     * @param material   当前主密钥材料（如 crypto.material()）
     * @param passphrase 保护口令（解析时须一致）
     */
    public static void exportTo(Path target, SecretKeyMaterial material, char[] passphrase) {
        CryptoSetting setting = new CryptoSetting();
        setting.setStoreType(KeyStoreType.DONGLE);
        setting.setKeyPolicy(KeyPolicy.CUSTOM);
        setting.setSecret(passphrase.clone());
        setting.setDonglePath(target.toAbsolutePath().toString());
        try {
            new DongleSecretKeyStore().save(material, setting);
        } finally {
            setting.wipeSecret();
        }
    }

    /**
     * 扫描可移动介质候选盘符（Windows 场景返回除系统盘外的根目录；类 Unix 返回空列表）。
     * 仅作候选提示，最终以实际挂载路径为准。
     *
     * @return 候选根目录列表
     */
    public static List<Path> removableDriveCandidates() {
        List<Path> candidates = new ArrayList<>();
        Path homeRoot = Paths.get(System.getProperty("user.home")).getRoot();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            if (root.equals(homeRoot)) {
                continue;
            }
            try {
                if (Files.isDirectory(root)) {
                    candidates.add(root);
                }
            } catch (Exception ignored) {
                // 无权限或介质不可读时跳过
            }
        }
        return candidates;
    }

    /**
     * 在候选盘符下查找默认命名的加密狗文件
     *
     * @return 首个存在的加密狗文件；未找到返回 null
     */
    public static Path findDefaultDongle() {
        for (Path root : removableDriveCandidates()) {
            Path candidate = root.resolve(DEFAULT_DONGLE_FILE);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 返回载体类型描述
     *
     * @return 类型名
     */
    @Override
    public String toString() {
        return KeyStoreType.DONGLE.name();
    }
}
