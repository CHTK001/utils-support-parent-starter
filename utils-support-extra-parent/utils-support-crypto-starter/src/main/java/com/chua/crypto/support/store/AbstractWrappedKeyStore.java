package com.chua.crypto.support.store;

import com.chua.crypto.support.CryptoException;
import com.chua.crypto.support.CryptoSetting;
import com.chua.crypto.support.KeyLifecycle;
import com.chua.crypto.support.key.KeyBlobCodec;
import com.chua.crypto.support.key.SecretKeyMaterial;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
* 封装型密钥载体抽象基类
*
* <p>负责载体文件的原子写入、安全擦除与生命周期（一次性读取即销毁）语义；
* 二进制封装格式统一委托 {@link KeyBlobCodec}（单一实现源）。
*
* <p>子类仅需提供魔数与载体路径。文件布局：
* <pre>
* [魔数4B][版本1B][策略标志1B][密钥ID8B][盐16B][IV12B][封装主密钥N字节][HMAC-SHA256 32B]
* </pre>
*
* @author CH
* @since 2026-08-26
 */
public abstract class AbstractWrappedKeyStore implements SecretKeyStore {

    /**
    * 获取载体魔数（密钥文件 CHKF）
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
    * 载体是否允许被销毁擦除（预留扩展位，当前恒为 true）
    *
    * @return true 表示允许擦除删除
     */
    protected boolean erasable() {
        return true;
    }

    /**
    * 写入密钥材料：委托 {@link KeyBlobCodec} 编码后原子落盘
     */
    @Override
    public void save(SecretKeyMaterial material, CryptoSetting setting) {
        Path target = carrierPath(setting);
        try {
            KeyFileResolver.ensureParent(target);
            byte[] out = KeyBlobCodec.encode(magic(), material, setting);
            writeAtomically(target, out);
            restrictPermissions(target);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("密钥载体写入失败: " + target.getFileName(), e);
        }
    }

    /**
    * 收紧载体文件权限：POSIX 文件系统设为仅属主读写；NTFS 等不支持 POSIX 权限的
    * 文件系统跳过（依赖目录 访问控制列表），失败不影响写入结果
    *
    * @param target 载体文件
     */
    private static void restrictPermissions(Path target) {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(target, ownerOnly);
        } catch (UnsupportedOperationException | IOException ignored) {
 // 窗口/FAT 等无 POSIX 权限语义的文件系统，依赖部署目录 访问控制列表
        }
    }

    /**
    * 加载密钥材料：读取后委托 {@link KeyBlobCodec} 校验解封；
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
            SecretKeyMaterial material = KeyBlobCodec.decode(magic(), all, setting);
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
