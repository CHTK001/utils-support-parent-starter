package com.chua.crypto.support;

import com.chua.crypto.support.key.ServerFingerprint;
import com.chua.crypto.support.store.FileSecretKeyStore;
import com.chua.crypto.support.store.KeyFileResolver;
import com.chua.crypto.support.store.SecretKeyStore;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥载体 SPI 测试
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
     * SPI 发现：file 别名应可解析
     */
    @Test
    void spiDiscovery() {
        assertNotNull(ServiceProvider.of(SecretKeyStore.class).getExtension("file"), "file 载体应可解析");
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
     * FatJar 相对路径解析 + 指纹稳定性
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
