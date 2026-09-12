package com.chua.crypto.support.launch;

import com.chua.common.support.reflection.ReflectUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
* 加密程序包引导器（Manifest 注入的 Main-类）
*
* <p>启动流程：
* <ol>
*   <li>反注入自检（{@link SelfDefense#install()}）</li>
*   <li>按优先级获取主密钥：校验服务器 → 私钥文件 → 包内密钥封装块</li>
*   <li>解密应用类与依赖包到进程私有临时目录（默认模式，退出自动清理）；
*       或 {@code -Dchua.crypto.lazy=true} 启用惰性解密类加载器</li>
*   <li>调用原始主类</li>
* </ol>
*
* <p>密钥来源配置（均支持 -D 系统属性与环境变量）：
* <table border="1">
*   <tr><th>形式</th><th>-D 属性</th><th>环境变量</th></tr>
*   <tr><td>字符串口令(pepper)</td><td>chua.crypto.pin</td><td>CHUA_CRYPTO_PIN</td></tr>
*   <tr><td>主机指纹固定值</td><td>chua.crypto.server-id</td><td>CHUA_CRYPTO_SERVER_ID</td></tr>
*   <tr><td>私钥文件</td><td>chua.crypto.key-file</td><td>CHUA_CRYPTO_KEY_FILE</td></tr>
*   <tr><td>校验服务器</td><td>chua.crypto.license-url / app-id</td><td>CHUA_CRYPTO_LICENSE_URL / CHUA_CRYPTO_APP_ID</td></tr>
* </table>
*
* @author CH
* @since 2026-08-26
 */
public final class CryptoLauncher {

    /**
    * 打包内嵌密钥块条目名
     */
    public static final String KEY_BLOB_ENTRY = "META-INF/chua-crypto.key";

    /**
    * 原始主类清单属性
     */
    public static final String ATTR_ORIGINAL_MAIN = "Chua-Original-Main-Class";

    /**
    * 口令系统属性
     */
    public static final String PROP_PIN = "chua.crypto.pin";

    /**
    * 口令环境变量
     */
    public static final String ENV_PIN = "CHUA_CRYPTO_PIN";

    /**
    * 固定服务器标识系统属性
     */
    public static final String PROP_SERVER_ID = "chua.crypto.server-id";

    /**
    * 固定服务器标识环境变量
     */
    public static final String ENV_SERVER_ID = "CHUA_CRYPTO_SERVER_ID";

    /**
    * 私钥文件系统属性
     */
    public static final String PROP_KEY_FILE = "chua.crypto.key-file";

    /**
    * 私钥文件环境变量
     */
    public static final String ENV_KEY_FILE = "CHUA_CRYPTO_KEY_FILE";

    /**
    * 校验服务器地址系统属性
     */
    public static final String PROP_LICENSE_URL = "chua.crypto.license-url";

    /**
    * 校验服务器地址环境变量
     */
    public static final String ENV_LICENSE_URL = "CHUA_CRYPTO_LICENSE_URL";

    /**
    * 应用标识系统属性
     */
    public static final String PROP_APP_ID = "chua.crypto.app-id";

    /**
    * 应用标识环境变量
     */
    public static final String ENV_APP_ID = "CHUA_CRYPTO_APP_ID";

    /**
    * 校验服务器响应签名密钥系统属性（生产必须配置）
     */
    public static final String PROP_LICENSE_SECRET = "chua.crypto.license-secret";

    /**
    * 校验服务器响应签名密钥环境变量
     */
    public static final String ENV_LICENSE_SECRET = "CHUA_CRYPTO_LICENSE_SECRET";

    /**
    * 惰性加载开关（默认关闭：解密装载模式对 Spring 组件扫描等完全兼容）
     */
    public static final String PROP_LAZY = "chua.crypto.lazy";

    /**
    * fatjar 依赖目录前缀
     */
    private static final String BOOT_LIB_PREFIX = "BOOT-INF/lib/";

    /**
    * fatjar 应用类根前缀
     */
    private static final String BOOT_CLASSES_PREFIX = "BOOT-INF/classes/";

    /**
    * 私有构造
     */
    private CryptoLauncher() {
    }

    /**
    * JVM 入口：失败时打印原因并以非零码退出
    *
    * @param args 应用启动参数
     */
    public static void main(String[] args) {
        try {
            launch(args);
        } catch (Throwable t) {
            System.err.println("[chua-crypto] 程序包启动失败: " + t.getMessage());
            System.exit(1);
        }
    }

    /**
    * 引导主流程
    *
    * @param args 应用启动参数
    * @throws Throwable 启动失败
     */
    static void launch(String[] args) throws Throwable {
        SelfDefense.install();
        File self = locateSelfJar();
        try (JarFile jar = new JarFile(self)) {
            Attributes attrs = mainAttributes(jar);
            String originalMain = attrs.getValue(ATTR_ORIGINAL_MAIN);
            if (originalMain == null || originalMain.isBlank()) {
                throw new IllegalStateException("缺少清单属性 " + ATTR_ORIGINAL_MAIN + "，请确认已由 chua-crypto 打包");
            }
 // 兼容：若记录的是 Boot 加载器则回退 启动-类
            if (originalMain.startsWith("org.springframework.boot.loader.")) {
                String startClass = attrs.getValue("Start-Class");
                if (startClass != null && !startClass.isBlank()) {
                    originalMain = startClass;
                }
            }

            byte[] master = resolveMaster(jar);
            Path tempDir = Files.createTempDirectory("chua-crypto-run");
            harden(tempDir);
            registerCleanup(tempDir);

            URLClassLoader appLoader = Boolean.parseBoolean(System.getProperty(PROP_LAZY, "false"))
                    ? buildLazyLoader(self, master)
                    : buildExtractedLoader(jar, master, tempDir);
            KeyShard.wipe(master);

            Thread.currentThread().setContextClassLoader(appLoader);
            Class<?> mainClass = ReflectUtils.forName(originalMain, appLoader);
            ReflectUtils.invoke(null, "main", void.class, String[].class, args);
        }
    }

    /**
    * 收紧目录权限（best-effort）：POSIX 文件系统设为仅属主读写执行；
    * 不支持 POSIX 语义的文件系统跳过，依赖部署环境 访问控制列表
    *
    * @param dir 目标目录
     */
    private static void harden(Path dir) {
        try {
            java.nio.file.attribute.PosixFilePermission[] perms = {
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE};
            Files.setPosixFilePermissions(dir, new java.util.HashSet<>(java.util.Arrays.asList(perms)));
        } catch (UnsupportedOperationException | IOException ignored) {
 // 窗口/FAT 等无 POSIX 权限语义的文件系统
        }
    }

    /**
    * 解封主密钥，优先级：
    * <ol>
    *   <li>校验服务器：POST 本机指纹 → 校验注册合法性 → 下发注册的私钥封装块</li>
    *   <li>私钥文件（CHKF）</li>
    *   <li>包内密钥封装块（策略以块内标志为准）</li>
    * </ol>
    *
    * @param jar 加密程序包
    * @return 32 字节主密钥
    * @throws IOException 读取失败
     */
    private static byte[] resolveMaster(JarFile jar) throws IOException {
        char[] pin = readSecret();
        String serverId = firstNonBlank(System.getProperty(PROP_SERVER_ID), System.getenv(ENV_SERVER_ID));

        String licenseUrl = firstNonBlank(System.getProperty(PROP_LICENSE_URL), System.getenv(ENV_LICENSE_URL));
        if (licenseUrl != null) {
            String appId = firstNonBlank(System.getProperty(PROP_APP_ID), System.getenv(ENV_APP_ID));
            if (appId == null) {
                appId = mainAttributes(jar).getValue(ATTR_ORIGINAL_MAIN);
            }
                        String licSecret = firstNonBlank(System.getProperty(PROP_LICENSE_SECRET), System.getenv(ENV_LICENSE_SECRET));
            byte[] blob = LicenseKeyClient.fetch(licenseUrl, appId,
                    PayloadCipher.fingerprint(serverId),
                    licSecret == null ? null : licSecret.toCharArray());
            return PayloadCipher.unwrapMaster(PayloadCipher.MAGIC_KEY_BLOB, blob,
                    pin == null ? new char[0] : pin, serverId);
        }

        String keyFilePath = firstNonBlank(System.getProperty(PROP_KEY_FILE), System.getenv(ENV_KEY_FILE));
        if (keyFilePath != null) {
            return loadCarrierBlob(Path.of(keyFilePath.trim()), "私钥文件", pin, serverId);
        }

        JarEntry blobEntry = jar.getJarEntry(KEY_BLOB_ENTRY);
        if (blobEntry == null) {
            throw new IllegalStateException("包内缺少密钥块 " + KEY_BLOB_ENTRY
                    + "：请配置校验服务器/私钥文件等密钥来源");
        }
        return PayloadCipher.unwrapMaster(PayloadCipher.MAGIC_KEY_BLOB,
                readAll(jar.getInputStream(blobEntry)), pin, serverId);
    }

    /**
    * 加载外置私钥文件封装块（CHKF）
    *
    * @param file      载体文件
    * @param desc      描述（用于错误消息）
    * @param pin       口令
    * @param serverId  固定服务器标识
    * @return 主密钥
    * @throws IOException 读取失败
     */
    private static byte[] loadCarrierBlob(Path file, String desc, char[] pin, String serverId) throws IOException {
        if (!Files.exists(file)) {
            throw new IllegalStateException(desc + "未找到: " + file);
        }
        byte[] blob = Files.readAllBytes(file);
        return PayloadCipher.unwrapMaster(PayloadCipher.MAGIC_KEY_BLOB, blob, pin, serverId);
    }

    /**
    * 默认模式：应用类与资源、依赖包整体解密到临时目录，构建标准类加载器。
    *
    * @param jar     加密程序包
    * @param master  主密钥
    * @param tempDir 进程私有临时根目录
    * @return 应用类加载器
    * @throws IOException 解密写出失败
     */
    private static URLClassLoader buildExtractedLoader(JarFile jar, byte[] master, Path tempDir)
            throws IOException {
        List<URL> urls = new ArrayList<>();
        Path classesDir = Files.createDirectories(tempDir.resolve("classes"));
        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith(BOOT_CLASSES_PREFIX) || name.endsWith("/")) {
                continue;
            }
            byte[] raw = readAll(jar.getInputStream(entry));
            byte[] bytes = PayloadCipher.isEncryptedEntry(raw)
                    ? PayloadCipher.decryptEntry(master, raw) : raw;
            Path target = classesDir.resolve(name.substring(BOOT_CLASSES_PREFIX.length()));
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
        urls.add(classesDir.toUri().toURL());

        Path libsDir = Files.createDirectories(tempDir.resolve("libs"));
        for (JarEntry entry : collectLibEntries(jar)) {
            byte[] bytes = decryptEntryBytes(jar, entry, master);
            Path libFile = Files.createTempFile(libsDir, "", "-" + fileName(entry));
            Files.write(libFile, bytes);
            urls.add(libFile.toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(new URL[0]), CryptoLauncher.class.getClassLoader());
    }

    /**
    * 惰性模式：应用 类 经 {@link EncryptedAppClassLoader} 按需解密，不落盘。
    *
    * @param self   包文件
    * @param master 主密钥
    * @return 惰性加载器
    * @throws IOException 解密失败
     */
    private static URLClassLoader buildLazyLoader(File self, byte[] master) throws IOException {
        Path libsDir = Files.createTempDirectory("chua-crypto-libs");
        registerCleanup(libsDir);

        List<URL> urls = new ArrayList<>();
        try (JarFile jar = new JarFile(self)) {
            for (JarEntry entry : collectLibEntries(jar)) {
                byte[] bytes = decryptEntryBytes(jar, entry, master);
                Path libFile = Files.createTempFile(libsDir, "", "-" + fileName(entry));
                Files.write(libFile, bytes);
                urls.add(libFile.toUri().toURL());
            }
        }
        URLClassLoader libsLoader = new URLClassLoader(urls.toArray(new URL[0]),
                CryptoLauncher.class.getClassLoader());
        return new EncryptedAppClassLoader(self, master.clone(), libsLoader);
    }

    /**
    * 收集 fatjar 依赖条目并按名称排序
    *
    * @param jar 加密程序包
    * @return 依赖条目列表
     */
    private static List<JarEntry> collectLibEntries(JarFile jar) {
        List<JarEntry> libs = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(BOOT_LIB_PREFIX) && name.endsWith(".jar")) {
                libs.add(entry);
            }
        }
        libs.sort(Comparator.comparing(JarEntry::getName));
        return libs;
    }

    /**
    * 解密条目（未加密则原样返回）
    *
    * @param jar   程序包
    * @param entry 条目
    * @param master 主密钥
    * @return 明文字节
    * @throws IOException 读取失败
     */
    private static byte[] decryptEntryBytes(JarFile jar, JarEntry entry, byte[] master) throws IOException {
        byte[] raw = readAll(jar.getInputStream(entry));
        return PayloadCipher.isEncryptedEntry(raw) ? PayloadCipher.decryptEntry(master, raw) : raw;
    }

    /**
    * 取条目文件名
    *
    * @param entry 条目
    * @return 文件名
     */
    private static String fileName(JarEntry entry) {
        return Path.of(entry.getName()).getFileName().toString();
    }

    /**
    * 注册 JVM 关闭钩子：递归删除临时目录
    *
    * @param dir 临时目录
     */
    private static void registerCleanup(Path dir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // 退出期清理失败可忽略
            }
        }, "chua-crypto-cleaner"));
    }

    /**
    * 定位自身程序包文件
    *
    * @return 加密包物理文件
    * @throws IllegalStateException 无法定位
     */
    private static File locateSelfJar() {
        ProtectionDomain domain = CryptoLauncher.class.getProtectionDomain();
        if (domain == null || domain.getCodeSource() == null) {
            throw new IllegalStateException("无法定位引导器代码源");
        }
        URI location = URI.create(domain.getCodeSource().getLocation().toString());
        String raw = location.toString();
        int bang = raw.indexOf('!');
        if (bang >= 0) {
            raw = raw.substring(0, bang);
        }
        File file = new File(URI.create(raw.startsWith("file:") ? raw : "file:" + raw));
        if (!file.exists()) {
            throw new IllegalStateException("程序包文件不存在: " + file);
        }
        return file;
    }

    /**
    * 读取 Manifest 主属性
    *
    * @param jar 程序包
    * @return 主属性
    * @throws IOException 读取失败
     */
    private static Attributes mainAttributes(JarFile jar) throws IOException {
        return jar.getManifest() != null
                ? jar.getManifest().getMainAttributes()
                : new Attributes();
    }

    /**
    * 读取口令：系统属性优先，其次环境变量
    *
    * @return 口令字符数组（可能为 空）
     */
    private static char[] readSecret() {
        String pin = System.getProperty(PROP_PIN);
        if (pin == null || pin.isBlank()) {
            pin = System.getenv(ENV_PIN);
        }
        return pin == null ? null : pin.toCharArray();
    }

    /**
    * 取第一个非空字符串
    *
    * @param values 候选值
    * @return 首个非空值或 空
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
    * 读取流全部字节
    *
    * @param in 输入流
    * @return 字节
    * @throws IOException 读取失败
     */
    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            return input.readAllBytes();
        }
    }
}
