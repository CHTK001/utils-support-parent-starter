package com.chua.crypto.support;

import com.chua.crypto.support.launch.CryptoLauncher;
import com.chua.crypto.support.launch.EncryptedAppClassLoader;
import com.chua.crypto.support.launch.PayloadCipher;
import com.chua.crypto.support.pack.JarEncryptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 程序包加密端到端测试：合成 SpringBoot 结构 FatJar → 加密 →
 * 校验清单/密钥块/条目密文 → 以引导类加载器解密加载类与资源
 *
 * @author CH
 * @since 2026-08-26
 */
class PackageEncryptionTest {

    /**
     * 临时目录
     */
    @TempDir
    Path tempDir;

    /**
     * 打包加密全流程：类/依赖包/配置文件均被加密，运行期可透明还原
     */
    @Test
    void packageEncryptAndLoadRoundTrip() throws Exception {
        Path sourceJar = buildSyntheticFatJar();
        Path secureJar = tempDir.resolve("app-secure.jar");

        char[] pin = "pack-pin".toCharArray();
        Crypto crypto = Crypto.create()
                .keyPolicy(KeyPolicy.CUSTOM)
                .secret(pin)
                .memory()
                .build();

        JarEncryptor.create()
                .source(sourceJar)
                .output(secureJar)
                .crypto(crypto)
                .encryptConfig(true)
                .execute();

        assertTrue(Files.exists(secureJar), "加密包应已生成");

        // ---- 清单改写校验 ----
        try (JarFile jar = new JarFile(secureJar.toFile())) {
            Attributes attrs = jar.getManifest().getMainAttributes();
            assertEquals(CryptoLauncher.class.getName(), attrs.getValue(Attributes.Name.MAIN_CLASS),
                    "Main-Class 应替换为引导器");
            assertEquals("com.demo.BootApplication", attrs.getValue(JarEncryptor.ATTR_ORIGINAL_MAIN));

            // ---- 密钥封装块校验：口令一致可解封出同一主密钥 ----
            byte[] blob = readEntry(jar, JarEncryptor.KEY_BLOB_ENTRY);
            byte[] unwrapped = PayloadCipher.unwrapMaster(PayloadCipher.MAGIC_KEY_BLOB, blob,
                    pin, null);
            assertArrayEquals(crypto.material().copyKey(), unwrapped, "解封主密钥应与打包时一致");
            assertThrows(IllegalStateException.class,
                    () -> PayloadCipher.unwrapMaster(PayloadCipher.MAGIC_KEY_BLOB, blob,
                            "wrong".toCharArray(), null),
                    "错误口令必须解封失败");

            // ---- 条目加密形态校验 ----
            byte[] appClass = readEntry(jar, "BOOT-INF/classes/com/demo/BootApplication.class");
            assertTrue(PayloadCipher.isEncryptedEntry(appClass), "应用类应为 CHKJ 密文");

            byte[] libJar = readEntry(jar, "BOOT-INF/lib/demo-lib.jar");
            assertTrue(PayloadCipher.isEncryptedEntry(libJar), "依赖包应整体为 CHKJ 密文");

            byte[] config = readEntry(jar, "BOOT-INF/classes/application.yml");
            assertTrue(PayloadCipher.isEncryptedEntry(config), "配置文件应随包加密");

            byte[] launcher = readEntry(jar, "com/chua/crypto/support/launch/CryptoLauncher.class");
            assertNotNull(launcher);
            assertTrue(!PayloadCipher.isEncryptedEntry(launcher), "引导器载荷必须保持明文");
        }

        // ---- 运行期：引导类加载器透明解密加载 ----
        EncryptedAppClassLoader appLoader = new EncryptedAppClassLoader(
                secureJar.toFile(), crypto.material().copyKey(),
                new URLClassLoader(new URL[0], getClass().getClassLoader()));
        try {
            Class<?> boot = appLoader.loadClass("com.demo.BootApplication");
            Object instance = boot.getDeclaredConstructor().newInstance();
            Method greet = boot.getMethod("greet");
            assertEquals("boot-ok", greet.invoke(instance), "加密类经解密后应可正常执行");

            Class<?> lib = appLoader.loadClass("com.demo.lib.DemoLib");
            assertEquals("lib-ok", lib.getMethod("tag").invoke(null), "依赖包内类应可加载执行");

            try (var in = appLoader.getResourceAsStream("application.yml")) {
                String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(text.contains("port: 8080"), "配置资源应透明解密为明文");
            }
        } finally {
            appLoader.close();
        }
    }

    /**
     * 构造合成 SpringBoot 结构 FatJar：
     * 清单(Main-Class) + 应用类 + application.yml + 依赖 jar
     */
    private Path buildSyntheticFatJar() throws Exception {
        Path sourceJar = tempDir.resolve("app-source.jar");

        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.putValue("Manifest-Version", "1.0");
        attrs.putValue("Main-Class", "com.demo.BootApplication");

        byte[] bootClass = compiledBytesOfClass(com.demo.BootApplication.class);
        byte[] libClass = compiledBytesOfClass(com.demo.lib.DemoLib.class);

        // 依赖 jar（内存构建）
        ByteArrayOutputStream libBuffer = new ByteArrayOutputStream();
        try (JarOutputStream libOut = new JarOutputStream(libBuffer)) {
            putEntry(libOut, "com/demo/lib/DemoLib.class", libClass);
        }
        byte[] libJarBytes = libBuffer.toByteArray();

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(sourceJar))) {
            putEntry(out, "META-INF/MANIFEST.MF", toBytes(manifest));
            putEntry(out, "BOOT-INF/", new byte[0]);
            putEntry(out, "BOOT-INF/classes/", new byte[0]);
            putEntry(out, "BOOT-INF/classes/com/", new byte[0]);
            putEntry(out, "BOOT-INF/classes/com/demo/", new byte[0]);
            putEntry(out, "BOOT-INF/classes/com/demo/BootApplication.class", bootClass);
            putEntry(out, "BOOT-INF/classes/application.yml",
                    "server:\n  port: 8080\napp:\n  name: demo\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            putEntry(out, "BOOT-INF/lib/", new byte[0]);
            putEntry(out, "BOOT-INF/lib/demo-lib.jar", libJarBytes);
        }
        return sourceJar;
    }

    /**
     * 写入条目（目录条目以空内容表示）
     */
    private void putEntry(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name));
        if (bytes.length > 0) {
            out.write(bytes);
        }
        out.closeEntry();
    }

    /**
     * 序列化清单
     */
    private byte[] toBytes(Manifest manifest) throws Exception {
        var buffer = new ByteArrayOutputStream();
        manifest.write(buffer);
        return buffer.toByteArray();
    }

    /**
     * 读取指定测试类编译产物的 class 字节（来自 test-classes 目录）
     */
    private byte[] compiledBytesOfClass(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "应能读取到测试类字节: " + resource);
            return in.readAllBytes();
        }
    }

    /**
     * 读取 jar 内条目
     */
    private byte[] readEntry(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, "条目应存在: " + name);
        try (var in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }
}
