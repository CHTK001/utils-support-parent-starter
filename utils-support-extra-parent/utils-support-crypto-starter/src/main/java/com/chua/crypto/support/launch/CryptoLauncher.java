package com.chua.crypto.support.launch;

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
import java.util.Base64;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 加密程序包引导器（Manifest 注入的 Main-Class）
 *
 * <p>启动流程：
 * <ol>
 *   <li>定位自身加密包文件（CodeSource）</li>
 *   <li>解封主密钥：优先 {@code -Dchua.crypto.dongle=} 加密狗载体；
 *       否则解封包内 {@code META-INF/chua-crypto.key} 封装块
 *       （策略以块内标志为准：SERVER_BOUND 自动采集本机指纹；CUSTOM 需提供口令）</li>
 *   <li>将 {@code BOOT-INF/lib/*.jar} 依赖包整体解密到进程私有临时目录并注册关闭钩子清理</li>
 *   <li>构建 {@link EncryptedAppClassLoader}（类/资源透明解密），调用原始主类</li>
 * </ol>
 *
 * <p>启动参数：
 * <pre>
 * java -jar app-secure.jar                                  # SERVER_BOUND 默认策略，本机直接运行
 * java -Dchua.crypto.pin=xxx -jar app-secure.jar            # CUSTOM 口令策略 / 加密狗口令
 * CHUA_CRYPTO_PIN=xxx java -jar app-secure.jar              # 环境变量等价形式
 * java -Dchua.crypto.server-id=node1 -jar app-secure.jar    # 容灾迁移固定指纹
 * java -Dchua.crypto.dongle=E:/key.dongle -jar ...          # U 盘加密狗启动
 * </pre>
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
     * 加密狗路径系统属性
     */
    public static final String PROP_DONGLE = "chua.crypto.dongle";

    /**
     * 外置密钥通道：stdin 读取 Base64 主密钥开关
     */
    public static final String PROP_KEY_FROM_STDIN = "chua.crypto.key-from-stdin";

    /**
     * 外置密钥通道：环境变量注入 Base64 主密钥
     */
    public static final String ENV_KEY_B64 = "CHUA_CRYPTO_KEY_B64";

    /**
     * FatJar 依赖目录前缀
     */
    private static final String BOOT_LIB_PREFIX = "BOOT-INF/lib/";

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
            t.printStackTrace();
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

            byte[] master = resolveMaster(jar);

            URLClassLoader libsLoader = buildLibsLoader(jar, self, master);
            EncryptedAppClassLoader appLoader = new EncryptedAppClassLoader(self, master, libsLoader);
            KeyShard.wipe(master);

            Thread.currentThread().setContextClassLoader(appLoader);
            Class<?> mainClass = Class.forName(originalMain, true, appLoader);
            try {
                mainClass.getMethod("main", String[].class).invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }

    /**
     * 解封主密钥，优先级：
     * <ol>
     *   <li>stdin 外置密钥（{@code -Dchua.crypto.key-from-stdin=true}，读取一行 Base64 主密钥，
     *       供外部/native 密钥提供方管道注入，口令不进入进程参数与环境）</li>
     *   <li>环境变量 {@code CHUA_CRYPTO_KEY_B64}</li>
     *   <li>加密狗载体（{@code -Dchua.crypto.dongle=}）</li>
     *   <li>包内密钥封装块（策略以块内标志为准）</li>
     * </ol>
     *
     * @param jar 加密程序包
     * @return 32 字节主密钥
     * @throws IOException 读取失败
     */
    private static byte[] resolveMaster(JarFile jar) throws IOException {
        byte[] external = readExternalKey();
        if (external != null) {
            return external;
        }

        char[] pin = readSecret();
        String serverId = firstNonBlank(System.getProperty(PROP_SERVER_ID), System.getenv(ENV_SERVER_ID));

        String donglePath = System.getProperty(PROP_DONGLE);
        if (donglePath != null && !donglePath.isBlank()) {
            Path dongle = Path.of(donglePath.trim());
            if (!Files.exists(dongle)) {
                throw new IllegalStateException("加密狗未找到: " + donglePath);
            }
            return PayloadCipher.unwrapMaster(PayloadCipher.MAGIC_DONGLE,
                    Files.readAllBytes(dongle), pin, serverId);
        }

        JarEntry blobEntry = jar.getJarEntry(KEY_BLOB_ENTRY);
        if (blobEntry == null) {
            throw new IllegalStateException("包内缺少密钥块 " + KEY_BLOB_ENTRY);
        }
        return PayloadCipher.unwrapMaster(PayloadCipher.MAGIC_KEY_BLOB,
                readAll(jar.getInputStream(blobEntry)), pin, serverId);
    }

    /**
     * 读取外置主密钥（Base64 32 字节）
     *
     * @return 主密钥；未启用外置通道时返回 null
     * @throws IOException stdin 读取失败
     */
    private static byte[] readExternalKey() throws IOException {
        boolean fromStdin = Boolean.parseBoolean(System.getProperty(PROP_KEY_FROM_STDIN, "false"));
        String base64 = fromStdin
                ? new String(System.in.readNBytes(64), java.nio.charset.StandardCharsets.UTF_8).trim()
                : System.getenv(ENV_KEY_B64);
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] master = Base64.getDecoder().decode(base64);
        if (master.length != 32) {
            KeyShard.wipe(master);
            throw new IllegalStateException("外置主密钥长度非法（期望 32 字节 Base64）");
        }
        return master;
    }

    /**
     * 解密依赖包到进程私有临时目录并构建加载器
     *
     * @param jar    加密程序包
     * @param self   包文件
     * @param master 主密钥
     * @return 依赖加载器
     * @throws IOException 解密写出失败
     */
    private static URLClassLoader buildLibsLoader(JarFile jar, File self, byte[] master) throws IOException {
        Path libsDir = Files.createTempDirectory("chua-crypto-libs");
        registerCleanup(libsDir);

        List<URL> urls = new ArrayList<>();
        List<JarEntry> libs = new ArrayList<>();
        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(BOOT_LIB_PREFIX) && name.endsWith(".jar")) {
                libs.add(entry);
            }
        }
        libs.sort(Comparator.comparing(JarEntry::getName));
        for (JarEntry entry : libs) {
            byte[] raw = readAll(jar.getInputStream(entry));
            byte[] bytes = PayloadCipher.isEncryptedEntry(raw)
                    ? PayloadCipher.decryptEntry(master, raw) : raw;
            String fileName = Path.of(entry.getName()).getFileName().toString();
            // createTempFile 生成 <随机>-<原名> 形态，保留 .jar 后缀语义
            Path libFile = Files.createTempFile(libsDir, "", "-" + fileName);
            Files.write(libFile, bytes);
            urls.add(libFile.toUri().toURL());
        }
        urls.add(self.toURI().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), CryptoLauncher.class.getClassLoader());
    }

    /**
     * 注册 JVM 关闭钩子：递归删除临时依赖目录
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
        }, "chua-crypto-libs-cleaner"));
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
     * @return 口令字符数组（可能为 null）
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
     * @return 首个非空值或 null
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
