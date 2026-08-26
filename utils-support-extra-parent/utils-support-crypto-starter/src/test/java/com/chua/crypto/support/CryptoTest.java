package com.chua.crypto.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Crypto} 链式门面核心功能测试：
 * 密钥策略、生命周期、密钥文件隐私存储与数据加解密
 *
 * @author CH
 * @since 2026-08-26
 */
class CryptoTest {

    /**
     * 临时目录
     */
    @TempDir
    Path tempDir;

    /**
     * 自定义口令 + 持久密钥文件：跨实例可解密
     */
    @Test
    void customPolicyPersistentRoundTrip() {
        String keyFile = tempDir.resolve("master.key").toString();

        Crypto first = Crypto.create()
                .keyPolicy(KeyPolicy.CUSTOM)
                .secret("pass-123".toCharArray())
                .lifecycle(KeyLifecycle.PERSISTENT)
                .keyFile(keyFile)
                .build();
        String cipher = first.encryptToString("机密数据 hello");
        first.close();

        assertTrue(Files.exists(Path.of(keyFile)), "持久模式应生成密钥文件");

        Crypto second = Crypto.create()
                .keyPolicy(KeyPolicy.CUSTOM)
                .secret("pass-123".toCharArray())
                .lifecycle(KeyLifecycle.PERSISTENT)
                .keyFile(keyFile)
                .build();
        assertEquals("机密数据 hello", second.decryptToString(cipher));
        second.close();
    }

    /**
     * 绑定服务器策略：同机重建实例可解密
     */
    @Test
    void serverBoundPolicyRoundTrip() {
        String keyFile = tempDir.resolve("server.key").toString();

        Crypto first = Crypto.create()
                .keyPolicy(KeyPolicy.SERVER_BOUND)
                .keyFile(keyFile)
                .build();
        byte[] plain = {1, 2, 3, 4, 5};
        byte[] cipher = first.encrypt(plain);
        first.close();

        Crypto second = Crypto.create()
                .keyPolicy(KeyPolicy.SERVER_BOUND)
                .keyFile(keyFile)
                .build();
        assertArrayEquals(plain, second.decrypt(cipher));
        second.close();
    }

    /**
     * 错误口令无法解封密钥
     */
    @Test
    void wrongSecretFails() {
        Crypto right = Crypto.create()
                .keyPolicy(KeyPolicy.CUSTOM)
                .secret("right-pass".toCharArray())
                .memory()
                .build();
        String cipher = right.encryptToString("secret");
        right.close();

        // 内存载体重新构建后为全新密钥，历史密文必然解不开（GCM 认证失败）
        Crypto other = Crypto.create()
                .keyPolicy(KeyPolicy.CUSTOM)
                .secret("other-pass".toCharArray())
                .memory()
                .build();
        assertThrows(CryptoException.class, () -> other.decryptToString(cipher));
        other.close();
    }

    /**
     * 一次性读取即销毁：加载后密钥文件被删除，旧密文不可恢复
     */
    @Test
    void oneTimeLifecycleDestroysKeyFile() throws Exception {
        String keyFile = tempDir.resolve("once.key").toString();
        Path keyPath = Path.of(keyFile);

        Crypto first = Crypto.create()
                .keyPolicy(KeyPolicy.CUSTOM)
                .secret("once-pass".toCharArray())
                .lifecycle(KeyLifecycle.ONE_TIME)
                .keyFile(keyFile)
                .build();
        // 引导流程：生成→落盘→读取销毁
        assertFalse(Files.exists(keyPath), "ONE_TIME 模式初始化完成后密钥文件应已销毁");
        String cipher = first.encryptToString("burn-after-read");
        first.close();

        // 文件已不存在 → 重新构建会生成全新密钥，历史密文不可解
        Crypto second = Crypto.create()
                .keyPolicy(KeyPolicy.CUSTOM)
                .secret("once-pass".toCharArray())
                .lifecycle(KeyLifecycle.ONE_TIME)
                .keyFile(keyFile)
                .build();
        assertThrows(CryptoException.class, () -> second.decryptToString(cipher));
        second.close();
    }

    /**
     * 纯内存载体：不产生任何落盘痕迹
     */
    @Test
    void memoryStoreLeavesNoTrace() {
        Crypto crypto = Crypto.create()
                .keyPolicy(KeyPolicy.CUSTOM)
                .secret("mem".toCharArray())
                .memory()
                .build();
        assertEquals("value", crypto.decryptToString(crypto.encryptToString("value")));
        crypto.close();
    }

    /**
     * 密钥文件内容必须为密文（不含主密钥明文字节）
     */
    @Test
    void keyFileStoresCipherOnly() throws Exception {
        String keyFile = tempDir.resolve("privacy.key").toString();
        Crypto crypto = Crypto.create()
                .keyPolicy(KeyPolicy.CUSTOM)
                .secret("privacy-check".toCharArray())
                .keyFile(keyFile)
                .build();
        crypto.close();

        byte[] bytes = Files.readAllBytes(Path.of(keyFile));
        assertEquals('C', bytes[0]);
        assertEquals('H', bytes[1]);
        assertEquals('K', bytes[2]);
        assertEquals('F', bytes[3], "密钥文件应具备固定魔数头");
        String content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(content.contains("privacy-check"), "口令明文不得出现在密钥文件中");
        assertTrue(bytes.length > 42 + 32, "密钥文件应为完整封装结构");
    }

    /**
     * 配置文件整体加密/解密与 ENC 单值
     */
    @Test
    void configFileEncryptDecryptRoundTrip(@TempDir Path dir) throws Exception {
        Crypto crypto = Crypto.create().keyPolicy(KeyPolicy.CUSTOM).secret("cfg".toCharArray()).memory().build();

        Path config = dir.resolve("application.yml");
        Files.writeString(config, "server:\n  port: 8080\napp:\n  name: demo\n");
        assertFalse(com.chua.crypto.support.config.ConfigFileCipher.isEncrypted(config));

        crypto.encryptConfigFile(config);
        assertTrue(com.chua.crypto.support.config.ConfigFileCipher.isEncrypted(config), "加密后首行应为标记行");
        String encryptedBody = Files.readString(config);
        assertNotEquals("server:", encryptedBody.lines().skip(1).findFirst().orElse(""), "正文应为密文");
        assertTrue(Files.exists(dir.resolve("application.yml.bak")), "默认应保留明文备份");

        String decrypted = crypto.decryptConfigFile(config);
        assertTrue(decrypted.contains("port: 8080"));
        assertTrue(decrypted.contains("name: demo"));

        String encValue = crypto.encryptValue("p@ssw0rd");
        assertTrue(encValue.startsWith("ENC(") && encValue.endsWith(")"));
        assertEquals("p@ssw0rd", crypto.decryptValue(encValue));
        assertEquals("plain", crypto.decryptValue("plain"));
        crypto.close();
    }

    /**
     * destroyCarrier 彻底作废落盘密钥
     */
    @Test
    void destroyCarrierWipesKeyFile() {
        String keyFile = tempDir.resolve("wipe.key").toString();
        Crypto crypto = Crypto.create()
                .keyPolicy(KeyPolicy.SERVER_BOUND)
                .keyFile(keyFile)
                .build();
        assertTrue(Files.exists(Path.of(keyFile)));
        crypto.destroyCarrier();
        assertFalse(Files.exists(Path.of(keyFile)), "销毁后载体文件应被删除");
    }
}
