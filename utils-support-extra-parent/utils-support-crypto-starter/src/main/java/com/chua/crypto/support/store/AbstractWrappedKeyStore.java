package com.chua.crypto.support.store;

import com.chua.crypto.support.CryptoException;
import com.chua.crypto.support.CryptoSetting;
import com.chua.crypto.support.KeyLifecycle;
import com.chua.crypto.support.KeyPolicy;
import com.chua.crypto.support.key.KeyProtector;
import com.chua.crypto.support.key.SecretKeyMaterial;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * 封装型密钥载体抽象基类
 *
 * <p>统一实现"密钥隐私存储"的载体二进制格式与读写流程，子类仅需提供魔数与载体路径。
 * 文件布局：
 * <pre>
 * [魔数4B][版本1B][策略标志1B][密钥ID8B][盐16B][IV12B][封装主密钥N字节][HMAC-SHA256 32B]
 * </pre>
 *
 * <p>主密钥以 KEK(AES-GCM) 封装存储，明文永不落盘；尾部 HMAC 覆盖除自身外全部字节防篡改。
 * 写入采用临时文件 + 原子移动保证一致性；读取校验失败一律抛出 {@link CryptoException}。
 *
 * @author CH
 * @since 2026-08-26
 */
public abstract class AbstractWrappedKeyStore implements SecretKeyStore {

    /**
     * 载体格式版本号
     */
    protected static final byte VERSION = 1;

    /**
     * 策略标志位：服务器绑定
     */
    protected static final byte FLAG_SERVER_BOUND = 0x01;

    /**
     * 固定头长度：魔数(4)+版本(1)+标志(1)+密钥ID(8)+盐(16)+IV(12)
     */
    protected static final int FIXED_HEADER_BYTES = 4 + 1 + 1 + 8 + 16 + 12;

    /**
     * 密钥 ID 长度（字节）
     */
    protected static final int KEY_ID_BYTES = SecretKeyMaterial.KEY_ID_LENGTH_BYTES;

    /**
     * 尾部 HMAC 长度（字节）
     */
    protected static final int TRAILING_MAC_BYTES = KeyProtector.MAC_BYTES;

    /**
     * 获取载体魔数（密钥文件 CHKF / 加密狗 CHKD）
     *
     * @return 4 字节魔数
     */
    protected abstract byte[] magic();

    /**
     * 解析载体路径
     *
     * @param setting 加密配置
     * @return 载体绝对路径
     */
    protected abstract Path carrierPath(CryptoSetting setting);

    /**
     * 载体是否允许被销毁擦除（物理介质如加密狗返回 false）
     *
     * @return true 表示允许擦除删除
     */
    protected boolean erasable() {
        return true;
    }

    /**
     * 写入密钥材料：派生 KEK → AES-GCM 封装 → 追加 HMAC → 原子落盘 → 收紧文件权限
     */
    @Override
    public void save(SecretKeyMaterial material, CryptoSetting setting) {
        Path target = carrierPath(setting);
        try {
            KeyFileResolver.ensureParent(target);
            byte[] salt = KeyProtector.randomSalt();
            byte[] iv = KeyProtector.randomIv();
            byte[] kek = KeyProtector.deriveKek(setting.getKeyPolicy(), setting, salt);

            byte[] header = new byte[FIXED_HEADER_BYTES];
            putMagic(header);
            header[4] = VERSION;
            header[5] = setting.getKeyPolicy() == KeyPolicy.SERVER_BOUND ? FLAG_SERVER_BOUND : 0x00;

            byte[] keyId = material.keyId();
            byte[] wrapped = KeyProtector.wrap(kek, iv, material.copyKey());

            byte[] body = new byte[header.length + keyId.length + salt.length + iv.length + wrapped.length];
            int offset = append(body, 0, header);
            offset = append(body, offset, keyId);
            offset = append(body, offset, salt);
            offset = append(body, offset, iv);
            append(body, offset, wrapped);

            byte[] mac = KeyProtector.mac(kek, body);
            byte[] out = new byte[body.length + mac.length];
            System.arraycopy(body, 0, out, 0, body.length);
            System.arraycopy(mac, 0, out, body.length, mac.length);

            writeAtomically(target, out);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("密钥载体写入失败: " + target.getFileName(), e);
        }
    }

    /**
     * 加载密钥材料：校验格式/HMAC → 按落盘策略派生 KEK → 解封；
     * 生命周期为一次性且载体可擦除时，读取成功后立即安全销毁落盘副本
     */
    @Override
    public SecretKeyMaterial load(CryptoSetting setting) {
        Path source = carrierPath(setting);
        if (!Files.exists(source)) {
            return null;
        }
        try {
            byte[] all = Files.readAllBytes(source);
            if (all.length < FIXED_HEADER_BYTES + TRAILING_MAC_BYTES) {
                throw new CryptoException("密钥载体已损坏（长度不足）");
            }
            verifyHeader(all);

            boolean serverBound = (all[5] & FLAG_SERVER_BOUND) != 0;
            KeyPolicy policy = serverBound ? KeyPolicy.SERVER_BOUND : KeyPolicy.CUSTOM;
            int cursor = 6;
            byte[] keyId = slice(all, cursor, KEY_ID_BYTES);
            cursor += KEY_ID_BYTES;
            byte[] salt = slice(all, cursor, KeyProtector.SALT_BYTES);
            cursor += KeyProtector.SALT_BYTES;
            byte[] iv = slice(all, cursor, KeyProtector.GCM_IV_BYTES);
            cursor += KeyProtector.GCM_IV_BYTES;
            byte[] wrapped = slice(all, cursor, all.length - TRAILING_MAC_BYTES - cursor);

            byte[] kek = KeyProtector.deriveKek(policy, setting, salt);
            byte[] expectedMac = KeyProtector.mac(kek, Arrays.copyOfRange(all, 0, all.length - TRAILING_MAC_BYTES));
            byte[] actualMac = slice(all, all.length - TRAILING_MAC_BYTES, TRAILING_MAC_BYTES);
            if (!KeyProtector.verifyMac(expectedMac, actualMac)) {
                throw new CryptoException("密钥载体完整性校验失败（可能被篡改或口令错误）");
            }

            SecretKeyMaterial material = SecretKeyMaterial.of(keyId, KeyProtector.unwrap(kek, iv, wrapped));
            if (setting.getLifecycle() == KeyLifecycle.ONE_TIME && erasable()) {
                destroy(setting);
            }
            return material;
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("密钥载体读取失败", e);
        }
    }

    /**
     * 载体是否存在
     */
    @Override
    public boolean exists(CryptoSetting setting) {
        return Files.exists(carrierPath(setting));
    }

    /**
     * 安全销毁载体：整文件覆写零后再删除，防止残留恢复
     */
    @Override
    public void destroy(CryptoSetting setting) {
        Path target = carrierPath(setting);
        if (!Files.exists(target)) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "rw")) {
            long length = raf.length();
            raf.seek(0);
            byte[] zeros = new byte[4096];
            long remaining = length;
            while (remaining > 0) {
                int n = (int) Math.min(zeros.length, remaining);
                raf.write(zeros, 0, n);
                remaining -= n;
            }
        } catch (IOException ignored) {
            // 覆写失败仍尝试删除
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new CryptoException("密钥载体删除失败: " + target.getFileName(), e);
        }
    }

    /**
     * 校验载体头：魔数与版本
     *
     * @param all 完整字节
     */
    private void verifyHeader(byte[] all) {
        byte[] expected = magic();
        for (int i = 0; i < expected.length; i++) {
            if (all[i] != expected[i]) {
                throw new CryptoException("密钥载体格式不匹配（魔数校验失败）");
            }
        }
        if (all[4] != VERSION) {
            throw new CryptoException("密钥载体版本不受支持: " + all[4]);
        }
    }

    /**
     * 将魔数写入缓冲区头部
     *
     * @param header 头缓冲区
     */
    private void putMagic(byte[] header) {
        System.arraycopy(magic(), 0, header, 0, magic().length);
    }

    /**
     * 追加字节数组到目标缓冲区
     *
     * @param target 目标缓冲区
     * @param offset 起始偏移
     * @param data   数据
     * @return 新偏移
     */
    private int append(byte[] target, int offset, byte[] data) {
        System.arraycopy(data, 0, target, offset, data.length);
        return offset + data.length;
    }

    /**
     * 截取字节区间副本
     *
     * @param source 源数组
     * @param from   起始下标
     * @param length 长度
     * @return 副本
     */
    private byte[] slice(byte[] source, int from, int length) {
        return Arrays.copyOfRange(source, from, from + length);
    }

    /**
     * 原子写入：先写同目录临时文件，再原子移动到目标位置
     *
     * @param target 目标路径
     * @param data   数据
     * @throws IOException IO 异常
     */
    private void writeAtomically(Path target, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), "chk", ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
