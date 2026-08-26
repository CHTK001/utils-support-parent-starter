package com.chua.crypto.support;

import com.chua.crypto.support.key.ServerFingerprint;
import com.chua.crypto.support.store.DongleSecretKeyStore;
import com.chua.crypto.support.store.FileSecretKeyStore;
import com.chua.crypto.support.store.KeyFileResolver;
import com.chua.crypto.support.store.SecretKeyStore;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥载体 SPI 与加密狗生成/解析测试
 *
 * @author CH
 * @since 2026-08-26
 */
class SecretKeyStoreTest {

    /**
     * 临时目录
     */
    @TempDir
    Path tempDir;

    /**
     * SPI 发现：file / dongle 两个别名均应可解析
     */
    @Test
    void spiDiscovery() {
        assertNotNull(ServiceProvider.of(SecretKeyStore.class).getExtension("file"), "file 载体应可解析");
        assertNotNull(ServiceProvider.of(SecretKeyStore.class).getExtension("dongle"), "dongle 载体应可解析");
    }

    /**
     * 加密狗生成 → 口令一致解析成功
     */
    @Test
    void dongleGenerateAndParse() {
        Path dongleFile = tempDir.resolve("chua-crypto.dongle");
        char[] pin = "dongle-pin".toCharArray();

        DongleSecretKeyStore.generate(dongleFile, pin);
        assertTrue(Files.exists(dongleFile), "加密狗文件应已生成");

        // 经链式 API 以加密狗为载体解密数据
        Crypto crypto = Crypto.create()
                .dongle(dongleFile.toString())
                .secret(pin)
                .build();
        String cipher = crypto.encryptToString("dongle-protected");
        assertEquals("dongle-protected", crypto.decryptToString(cipher));
        assertTrue(Files.exists(dongleFile), "加密狗为持久载体，使用后不得删除");
        crypto.close();
    }

    /**
     * 加密狗口令错误时拒绝解析（GCM/HMAC 校验失败）
     */
    @Test
    void dongleWrongPinRejected() {
        Path dongleFile = tempDir.resolve("bad-pin.dongle");
        DongleSecretKeyStore.generate(dongleFile, "right-pin".toCharArray());

        assertThrows(CryptoException.class, () -> Crypto.create()
                .dongle(dongleFile.toString())
                .secret("wrong-pin".toCharArray())
                .build());
    }

    /**
     * 加密狗载体缺失时报错而非隐式创建
     */
    @Test
    void missingDongleRejected() {
        Path missing = tempDir.resolve("not-exist.dongle");
        assertThrows(CryptoException.class, () -> Crypto.create()
                .dongle(missing.toString())
                .secret("pin".toCharArray())
                .build());
        assertFalse(Files.exists(missing), "缺失的加密狗不应被隐式创建");
    }

    /**
     * 文件载体：save/load/destroy 基本语义
     */
    @Test
    void fileStoreLifecycle() {
        FileSecretKeyStore store = new FileSecretKeyStore();
        CryptoSetting setting = new CryptoSetting();
        setting.setKeyPolicy(KeyPolicy.CUSTOM);
        setting.setSecret("store-pass".toCharArray());
        setting.setKeyFile(tempDir.resolve("carrier.key").toString());

        assertFalse(store.exists(setting));
        assertNull(store.load(setting));

        store.save(com.chua.crypto.support.key.SecretKeyMaterial.generate(), setting);
        assertTrue(store.exists(setting));
        assertNotNull(store.load(setting));

        store.destroy(setting);
        assertFalse(store.exists(setting));
    }

    /**
     * FatJar 相对路径解析：默认落用户目录，显式相对路径优先命中工作目录
     */
    @Test
    void keyFileResolution() {
        Path resolved = KeyFileResolver.resolve(null);
        assertTrue(resolved.toString().endsWith("master.key"), "缺省路径应指向 master.key");

        String jarRelative = KeyFileResolver.resolve("security/app.key").toString();
        assertTrue(jarRelative.endsWith("app.key"));
        assertNotNull(ServerFingerprint.capture().value());
        assertEquals(64, ServerFingerprint.capture().value().length(), "SHA-256 指纹应为 64 位十六进制");
        assertEquals(ServerFingerprint.of("node-1").value(), ServerFingerprint.of("node-1").value(),
                "固定标识指纹应稳定一致");
    }
}
