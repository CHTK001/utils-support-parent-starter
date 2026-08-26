package com.chua.crypto.support.pack;

import com.chua.crypto.support.Crypto;
import com.chua.crypto.support.launch.CryptoLauncher;
import com.chua.crypto.support.codec.DataCipher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * 可执行程序包加密器（SpringBoot FatJar / 普通 Jar）
 *
 * <p>将可运行程序整体加密为自保护发行包：
 * <ul>
 *   <li>类文件 — {@code *.class} 全部按 CHKJ 格式逐条目加密</li>
 *   <li>依赖包 — FatJar 内 {@code BOOT-INF/lib/*.jar} 整体加密（依赖包加密）</li>
 *   <li>配置文件 — 链式开关 {@code encryptConfig(true)} 后，application/bootstrap 等配置一并加密，
 *       运行期由引导类加载器透明解密，磁盘始终密文</li>
 *   <li>主密钥 — 以 CHKF 封装块内嵌至 {@code META-INF/chua.crypto.key}：
 *       SERVER_BOUND 策略下密钥与打包机绑定，程序拷贝到其他服务器无法启动；
 *       CUSTOM 策略下需口令启动；亦可在启动时改用私钥文件解封</li>
 *   <li>引导器 — 注入零依赖的 {@code launch} 包并接管 Manifest Main-Class，
 *       原 Main-Class 记录于 {@code Chua-Original-Main-Class}</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * Crypto crypto = Crypto.create().keyPolicy(KeyPolicy.SERVER_BOUND).build();
 *
 * JarEncryptor.create()
 *         .source("app.jar")              // SpringBoot FatJar 或普通可执行 Jar
 *         .output("app-secure.jar")
 *         .crypto(crypto)
 *         .encryptConfig(true)            // 配置文件随包一起加密
 *         .exclude("BOOT-INF/classes/static/")
 *         .execute();
 *
 * // 发行后运行：java -jar app-secure.jar（SERVER_BOUND 本机免参；CUSTOM 加 -Dchua.crypto.pin=xxx）
 * }</pre>
 *
 * @author CH
 * @since 2026-08-26
 */
public class JarEncryptor {

    /**
     * 打包内嵌密钥块条目名
     */
    public static final String KEY_BLOB_ENTRY = CryptoLauncher.KEY_BLOB_ENTRY;

    /**
     * 原始主类清单属性
     */
    public static final String ATTR_ORIGINAL_MAIN = CryptoLauncher.ATTR_ORIGINAL_MAIN;

    /**
     * SpringBoot 真实主类属性名
     */
    public static final String START_CLASS_ATTR = "Start-Class";

    /**
     * 引导器主类名
     */
    private static final String LAUNCHER_CLASS = CryptoLauncher.class.getName();

    /**
     * 引导器包路径前缀（保持明文注入）
     */
    private static final String LAUNCH_PACKAGE = "com/chua/crypto/support/launch/";

    /**
     * FatJar 依赖目录前缀
     */
    private static final String BOOT_LIB_PREFIX = "BOOT-INF/lib/";

    /**
     * 需剔除的签名文件模式
     */
    private static final Pattern SIGNATURE_FILE = Pattern.compile("^META-INF/.*\\.(SF|DSA|RSA|EC)$");

    /**
     * 配置文件条目模式（encryptConfig 开启时生效）
     */
    private static final Pattern CONFIG_ENTRY =
            Pattern.compile("^(application|bootstrap)[-.\\w]*\\.(yml|yaml|properties)$");

    /**
     * 源程序包
     */
    private Path source;

    /**
     * 输出加密包
     */
    private Path output;

    /**
     * 已初始化的加密门面
     */
    private Crypto crypto;

    /**
     * 是否同时加密配置文件
     */
    private boolean encryptConfig;

    /**
     * 是否加密依赖包(BOOT-INF/lib/*.jar)，默认加密；关闭后依赖包明文保留
     */
    private boolean encryptLibs = true;

    /**
     * 是否对应用 class 做混淆处理（剥离调试信息）
     */
    private boolean obfuscate;

    /**
     * 混淆时是否重命名私有成员（需自行评估反射兼容性）
     */
    private boolean renamePrivates;

    /**
     * 源包是否为 SpringBoot 布局（execute 期间判定）
     */
    private boolean springBootLayout;

    /**
     * 是否内嵌密钥封装块（默认 true；关闭后包必须依赖 校验服务器/私钥文件/管道 获取密钥）
     */
    private boolean embedKeyBlob = true;

    /**
     * 明文保留前缀排除列表
     */
    private final List<String> excludes = new ArrayList<>();

    /**
     * 私有构造，统一从 {@link #create()} 进入
     */
    private JarEncryptor() {
    }

    /**
     * 创建链式构建入口
     *
     * @return 加密器
     */
    public static JarEncryptor create() {
        return new JarEncryptor();
    }

    /**
     * 设置源程序包
     *
     * @param source 源 jar 路径
     * @return 当前对象
     */
    public JarEncryptor source(String source) {
        this.source = Path.of(source);
        return this;
    }

    /**
     * 设置源程序包
     *
     * @param source 源 jar 路径
     * @return 当前对象
     */
    public JarEncryptor source(Path source) {
        this.source = source;
        return this;
    }

    /**
     * 设置输出加密包路径
     *
     * @param output 输出路径
     * @return 当前对象
     */
    public JarEncryptor output(String output) {
        this.output = Path.of(output);
        return this;
    }

    /**
     * 设置输出加密包路径
     *
     * @param output 输出路径
     * @return 当前对象
     */
    public JarEncryptor output(Path output) {
        this.output = output;
        return this;
    }

    /**
     * 绑定已初始化的加密门面（提供主密钥与策略）
     *
     * @param crypto 加密门面
     * @return 当前对象
     */
    public JarEncryptor crypto(Crypto crypto) {
        this.crypto = crypto;
        return this;
    }

    /**
     * 设置配置文件是否随包一起加密
     *
     * @param encryptConfig true 表示加密 application/bootstrap 等配置条目
     * @return 当前对象
     */
    public JarEncryptor encryptConfig(boolean encryptConfig) {
        this.encryptConfig = encryptConfig;
        return this;
    }

    /**
     * 设置依赖包是否加密（默认 true）
     *
     * @param encryptLibs false 表示 BOOT-INF/lib/*.jar 明文保留
     * @return 当前对象
     */
    public JarEncryptor encryptLibs(boolean encryptLibs) {
        this.encryptLibs = encryptLibs;
        return this;
    }

    /**
     * 开启应用 class 混淆（剥离调试信息：源文件名/行号表/局部变量表）
     *
     * @param obfuscate true 表示启用
     * @return 当前对象
     */
    public JarEncryptor obfuscate(boolean obfuscate) {
        this.obfuscate = obfuscate;
        return this;
    }

    /**
     * 混淆时进一步重命名私有成员（反射框架如 MyBatis/Jackson 依赖私有字段名时会失效，谨慎开启）
     *
     * @param renamePrivates true 表示启用
     * @return 当前对象
     */
    public JarEncryptor renamePrivates(boolean renamePrivates) {
        this.renamePrivates = renamePrivates;
        return this;
    }

    /**
     * 是否内嵌密钥封装块（默认 true）
     *
     * @param embedKeyBlob false 表示不内嵌，运行期必须提供外部密钥来源
     *                     （校验服务器/私钥文件）
     * @return 当前对象
     */
    public JarEncryptor embedKeyBlob(boolean embedKeyBlob) {
        this.embedKeyBlob = embedKeyBlob;
        return this;
    }

    /**
     * 追加明文保留排除项（条目路径前缀匹配）
     *
     * @param prefixes 前缀列表（如 BOOT-INF/classes/static/）
     * @return 当前对象
     */
    public JarEncryptor exclude(String... prefixes) {
        excludes.addAll(Arrays.asList(prefixes));
        return this;
    }

    /**
     * 执行打包加密
     *
     * @return 输出加密包路径
     */
    public Path execute() {
        validate();
        springBootLayout = isSpringBootLayout();
        try (JarFile input = new JarFile(source.toFile(), false)) {
            Manifest manifest = patchManifest(input.getManifest());

            Files.createDirectories(output.toAbsolutePath().getParent());
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
                writeEntry(out, "META-INF/MANIFEST.MF", toManifestBytes(manifest));
                if (embedKeyBlob) {
                    writeEntry(out, KEY_BLOB_ENTRY, buildKeyBlob());
                }
                injectLaunchPayload(out);
                for (java.util.Enumeration<JarEntry> entries = input.entries(); entries.hasMoreElements(); ) {
                    copyEntry(out, input, entries.nextElement());
                }
            }
            return output;
        } catch (IOException e) {
            throw new com.chua.crypto.support.CryptoException("程序包加密失败: " + source, e);
        }
    }

    /**
     * 注入零依赖引导器载荷：从当前类路径读取 launch 包全部类文件（含内部类），
     * 以明文形态写入输出包根目录，保证其在应用类加载器建立前即可运行
     *
     * @param out 目标流
     * @throws IOException 载荷注入失败
     */
    private void injectLaunchPayload(JarOutputStream out) throws IOException {
        ClassLoader classLoader = JarEncryptor.class.getClassLoader();
        for (Class<?> type : List.of(CryptoLauncher.class,
                com.chua.crypto.support.launch.EncryptedAppClassLoader.class,
                com.chua.crypto.support.launch.PayloadCipher.class,
                com.chua.crypto.support.launch.KeyShard.class,
                com.chua.crypto.support.launch.LicenseKeyClient.class,
                com.chua.crypto.support.launch.SelfDefense.class)) {
            writeClassHierarchy(out, classLoader, type);
        }
    }

    /**
     * 递归写入类及其全部声明内部类的字节（载荷自身剥离调试信息）
     *
     * @param out         目标流
     * @param classLoader 类路径资源加载器
     * @param type        目标类
     * @throws IOException 写入失败
     */
    private void writeClassHierarchy(JarOutputStream out, ClassLoader classLoader, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("引导器载荷缺失: " + resource);
            }
            byte[] bytes = readAll(in);
            if (obfuscate) {
                bytes = ClassObfuscator.stripDebug(bytes);
            }
            writeEntry(out, resource, bytes);
        }
        for (Class<?> inner : type.getDeclaredClasses()) {
            writeClassHierarchy(out, classLoader, inner);
        }
    }

    /**
     * 参数校验
     */
    private void validate() {
        if (source == null || !Files.exists(source)) {
            throw new com.chua.crypto.support.CryptoException("源程序包不存在");
        }
        if (output == null) {
            throw new com.chua.crypto.support.CryptoException("请指定输出路径 output(...)");
        }
        if (crypto == null || !crypto.isInitialized()) {
            throw new com.chua.crypto.support.CryptoException("请绑定已初始化的加密门面 crypto(...)");
        }
        if (!isFatJar(source)) {
            throw new com.chua.crypto.support.CryptoException(
                    "源程序包缺少可执行 Manifest（Main-Class），仅支持可执行 FatJar/Jar");
        }
    }

    /**
     * 判断是否可执行包（存在 Main-Class）
     *
     * @param jarPath 包路径
     * @return true 表示可执行
     */
    private boolean isFatJar(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            return manifest != null
                    && manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 判断源包是否为 SpringBoot 布局（存在 BOOT-INF/classes）
     *
     * @return true 表示 SpringBoot 布局
     */
    private boolean isSpringBootLayout() {
        try (JarFile jar = new JarFile(source.toFile())) {
            return jar.getEntry("BOOT-INF/classes/") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 判断条目是否为应用自身 class（混淆作用域：排除引导器与依赖包）
     *
     * @param name 条目名
     * @return true 表示应用 class
     */
    private boolean isAppClass(String name) {
        if (!name.endsWith(".class") || name.startsWith(LAUNCH_PACKAGE)) {
            return false;
        }
        return springBootLayout ? name.startsWith("BOOT-INF/classes/") : !name.startsWith(BOOT_LIB_PREFIX);
    }

    /**
     * 改写清单：Main-Class 替换为引导器，原主类写入专属属性
     *
     * @param original 原清单
     * @return 新清单
     * @throws IOException 清单缺失
     */
    private Manifest patchManifest(Manifest original) throws IOException {
        if (original == null) {
            throw new IOException("源程序包缺少 MANIFEST.MF");
        }
        Manifest patched = new Manifest(original);
        Attributes attrs = patched.getMainAttributes();
        String originalMain = attrs.getValue(Attributes.Name.MAIN_CLASS);
        // SpringBoot 发行包的 Main-Class 是加载器(JarLauncher)，真实主类在 Start-Class
        if (originalMain != null && originalMain.startsWith("org.springframework.boot.loader.")) {
            String startClass = attrs.getValue(START_CLASS_ATTR);
            if (startClass != null && !startClass.isBlank()) {
                originalMain = startClass;
            }
        }
        attrs.putValue(ATTR_ORIGINAL_MAIN, originalMain);
        attrs.putValue("Main-Class", LAUNCHER_CLASS);
        attrs.putValue("Chua-Crypto-Pack", "1");
        return patched;
    }

    /**
     * 生成内嵌主密钥封装块（CHKF 格式，策略取自当前加密门面设置）
     *
     * @return 封装块字节
     */
    private byte[] buildKeyBlob() {
        return com.chua.crypto.support.key.KeyBlobCodec.encode(
                new byte[]{'C', 'H', 'K', 'F'},
                crypto.material(),
                crypto.setting());
    }

    /**
     * 拷贝单个条目：按规则决定明文保留或加密重写
     *
     * @param out   目标流
     * @param input 源包
     * @param entry 条目
     * @throws IOException 读写失败
     */
    private void copyEntry(JarOutputStream out, JarFile input, JarEntry entry) throws IOException {
        String name = entry.getName();
        if (entry.isDirectory()) {
            JarEntry dir = new JarEntry(name);
            out.putNextEntry(dir);
            out.closeEntry();
            return;
        }
        if ("META-INF/MANIFEST.MF".equals(name)
                || KEY_BLOB_ENTRY.equals(name)
                || SIGNATURE_FILE.matcher(name).matches()) {
            return;
        }

        byte[] raw = readAll(input.getInputStream(entry));
        if (DataCipher.isTagged(raw)) {
            // 已加密条目直接透传（重复打包安全）
            writeEntry(out, name, raw);
            return;
        }
        if (shouldKeepPlain(name, raw)) {
            writeEntry(out, name, raw);
            return;
        }
        if (obfuscate && isAppClass(name) && !springBootLayoutExcluded(name)) {
            raw = ClassObfuscator.obfuscate(raw, renamePrivates);
        }
        writeEntry(out, name, DataCipher.encryptTagged(crypto.material().copyKey(), raw));
    }

    /**
     * 混淆排除判断（当前保留扩展位：目录级排除）
     *
     * @param name 条目名
     * @return true 表示跳过混淆
     */
    private boolean springBootLayoutExcluded(String name) {
        return excludes.stream().anyMatch(prefix ->
                name.startsWith(prefix) && name.endsWith(".class"));
    }

    /**
     * 判定条目是否保持明文：引导器载荷、显式排除前缀、非目标类型
     *
     * @param name 条目名
     * @param raw  条目内容
     * @return true 表示保留明文
     */
    private boolean shouldKeepPlain(String name, byte[] raw) {
        // 引导器必须明文（先于类加载器工作）
        if (name.startsWith(LAUNCH_PACKAGE)) {
            return true;
        }
        for (String prefix : excludes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        boolean isClass = name.endsWith(".class");
        boolean isLibJar = encryptLibs && name.startsWith(BOOT_LIB_PREFIX) && name.endsWith(".jar");
        boolean isConfig = encryptConfig && CONFIG_ENTRY
                .matcher(lastSegment(name))
                .matches();
        return !(isClass || isLibJar || isConfig);
    }

    /**
     * 取路径最后一段
     *
     * @param path 条目路径
     * @return 文件名
     */
    private String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * 写入单条目
     *
     * @param out   目标流
     * @param name  条目名
     * @param bytes 内容
     * @throws IOException 写出失败
     */
    private void writeEntry(JarOutputStream out, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(System.currentTimeMillis());
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    /**
     * 序列化清单
     *
     * @param manifest 清单
     * @return 字节
     * @throws IOException 序列化失败
     */
    private byte[] toManifestBytes(Manifest manifest) throws IOException {
        var buffer = new java.io.ByteArrayOutputStream();
        manifest.write(buffer);
        return buffer.toByteArray();
    }

    /**
     * 读取流全部字节
     *
     * @param in 输入流
     * @return 字节
     * @throws IOException 读取失败
     */
    private byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            return input.readAllBytes();
        }
    }
}
