package com.chua.osgi.support.source;

import com.chua.common.support.collection.wrap.Wraps;
import lombok.extern.slf4j.Slf4j;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 把源码目录下的 {@code .java} 文件编译为 SPI 扩展点实现并注册到
 * {@link com.chua.common.support.spi.ServiceProvider}。
 *
 * <p><b>为什么用独立 ClassLoader 而不是直接进 Felix</b>：OSGi bundle 要解析
 * {@code Import-Package} 版本区间，动态产物的依赖版本往往对不上，表现为长时间停在
 * {@code INSTALLED} 且日志无明确原因。SPI 扩展点只要求「某个接口的某个名字能取到实现」，
 * 用独立 ClassLoader 承载编译产物即可满足，且卸载时可整批丢弃——
 * 这正是热重载需要的语义。</p>
 *
 * <p><b>类路径如何拼</b>：用当前线程上下文 ClassLoader（即应用 ClassLoader）的
 * URL 作为基础，因此源码里可以直接 import 应用侧已有的 SPI 接口与工具类。</p>
 *
 * <p><b>热重载如何做</b>：每轮编译新建一个 {@link SourceClassLoader}；
 * 注册前先把上一轮的扩展名从 SPI 表里注销，再注册新实例。
 * 旧 ClassLoader 在无引用后由 GC 回收——这是与「把类装进 Felix 再 update」相比
 * 更简单可靠的地方：不需要处理 bundle 标识与依赖重解析。</p>
 *
 * <p>编译诊断（错误/警告）以日志输出，编译失败时<b>保留上一轮注册</b>，
 * 不做部分注册，避免半成品状态。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class SourceJavaCompiler {

    /**
     * Spring Boot fat jar 的嵌套 jar URL 协议前缀（实测 {@code LaunchedClassLoader} 暴露的 URL 形如
     * {@code jar:nested:/path/app.jar/!BOOT-INF/lib/xxx.jar!/}）。
     */
    private static final String NESTED_JAR_PROTOCOL = "jar";

    /**
     * 嵌套条目起始标记：外层 jar 路径之后紧跟 {@code !BOOT-INF/...}。
     *
     * <p>实测（198 上 eduai fat jar）URL 形如
     * {@code jar:nested:/path/app.jar/!BOOT-INF/lib/xxx.jar!/}：
     * 分隔处只有一个 {@code !}，条目结尾是 {@code !/}。
     * 故用 {@code "!BOOT-INF/"} 定位比固定长度偏移更可靠。</p>
     */
    private static final String NESTED_MARKER = "!BOOT-INF/";

    /**
     * 嵌套 URL 的尾部结束标记。
     */
    private static final String NESTED_TAIL = "!/";

    /**
     * 一轮编译的结果。
     *
     * <p><b>为什么要单独表达「成功与否」而不只看声明列表</b>：源码里把扩展点字段
     * 全部删掉时，编译是<b>成功</b>的但声明列表为空；而编译失败时列表同样为空。
     * 调用方若用「列表为空即失败」判断，就会把「字段删光」误判成编译失败，
     * 从而跳过注销——结果源码已删的扩展点仍留在 SPI 表里，且不报任何错。</p>
     *
     * @param success     是否编译成功
     * @param declarations 产出的扩展点声明，成功时可能为空
     */
    public record CompileOutcome(boolean success, List<SourceExtensionFactory> declarations) {

        /**
         * 构造成功结果。
         *
         * @param declarations 扩展点声明
         * @return 成功结果
         */
        public static CompileOutcome ok(List<SourceExtensionFactory> declarations) {
            return new CompileOutcome(true, List.copyOf(declarations));
        }

        /**
         * 构造失败结果。
         *
         * @return 失败结果，不含任何声明
         */
        public static CompileOutcome failed() {
            return new CompileOutcome(false, List.of());
        }
    }

    /**
     * 源码目录。
     */
    private final Path sourceDirectory;

    /**
     * 编译产物输出目录，每次重载使用独立子目录以免旧 class 残留。
     */
    private final Path outputDirectory;

    /**
     * 应用 ClassLoader，提供源码可用的 classpath 基础。
     */
    private final ClassLoader applicationClassLoader;

    /**
     * 上一轮成功发布的扩展点，重载时据此注销。
     */
    private final List<SourceExtensionFactory> published = Wraps.list();

    /**
     * 上一轮的 ClassLoader，重载完成后丢弃引用以便回收。
     */
    private volatile ClassLoader currentLoader;

    /**
     * 本轮编译产物目录，下一轮开始前清理。
     *
     * <p>不清理的话每轮重载都留一份无主 class 文件，实测 15 轮即堆积到数百 MB。</p>
     */
    private volatile Path currentRoundDir;

    /**
     * 创建源码编译器。
     *
     * @param sourceDirectory        源码目录
     * @param outputDirectory        编译产物输出目录
     * @param applicationClassLoader 应用 ClassLoader；为 {@code null} 时取上下文 ClassLoader
     */
    public SourceJavaCompiler(Path sourceDirectory, Path outputDirectory,
                              ClassLoader applicationClassLoader) {
        this.sourceDirectory = sourceDirectory;
        this.outputDirectory = outputDirectory;
        this.applicationClassLoader = applicationClassLoader != null
                ? applicationClassLoader
                : Thread.currentThread().getContextClassLoader();
    }

    /**
     * 执行一轮编译并返回带成功标记的结果。
     *
     * <p>调用方需要区分「编译失败」与「编译成功但没有扩展点」——
     * 二者的声明列表都是空的，但前者必须保留上一轮注册、后者必须执行注销。
     * 详见 {@link CompileOutcome}。</p>
     *
     * @return 编译结果
     */
    public CompileOutcome compileWithOutcome() {
        List<Path> sources = listJavaSources();
        if (sources.isEmpty()) {
            // 没有源码也算成功：此时应注销上一轮（源码被删光）
            return CompileOutcome.ok(List.of());
        }
        Path classesDir = prepareOutputDir();
        if (!compile(sources, classesDir)) {
            log.warn("[osgi-source] 编译失败，保留上一轮状态（{} 个扩展点）", published.size());
            return CompileOutcome.failed();
        }
        ClassLoader loader;
        try {
            loader = new SourceClassLoader(classesDir, applicationClassLoader);
        } catch (MalformedURLException e) {
            log.error("[osgi-source] 创建源码 ClassLoader 失败: {}", classesDir, e);
            return CompileOutcome.failed();
        }
        List<SourceExtensionFactory> batch = Wraps.list();
        try {
            collectDeclarations(classesDir, loader, batch);
        } catch (IOException | ReflectiveOperationException | LinkageError e) {
            log.error("[osgi-source] 读取源码扩展点声明失败，保留上一轮状态", e);
            return CompileOutcome.failed();
        }
        currentLoader = loader;
        if (batch.isEmpty()) {
            log.warn("[osgi-source] 源码已编译但没有任何 @SourceSpi 宿主类");
        } else {
            log.info("[osgi-source] 编译完成，产出 {} 个扩展点声明（{} 个源文件）",
                    batch.size(), sources.size());
        }
        return CompileOutcome.ok(batch);
    }

    /**
     * 执行一轮编译，产出本轮的扩展点声明。
     *
     * <p>本方法<b>只编译不注册</b>——注册由
     * {@link SourceExtensionResolver} 统一完成，这样才能用可注销的
     * {@link com.chua.common.support.spi.definition.ServiceDefinition} 路径发布。</p>
     *
     * <p>目录不存在或无源码时返回空列表，不视为错误，配置项可以先开起来再放源码。</p>
     *
     * @return 本轮扩展点声明；编译失败时同样返回空列表，
     *         故仅用于不关心成功与否的场景，热重载路径请用 {@link #compileWithOutcome()}
     */
    public List<SourceExtensionFactory> compile() {
        return compileWithOutcome().declarations();
    }

    /**
     * 记录本轮已发布的扩展点，供下一轮注销。
     *
     * @param factories 本轮发布的扩展点声明
     */
    public void markPublished(List<SourceExtensionFactory> factories) {
        published.clear();
        published.addAll(factories);
    }

    /**
     * 取出上一轮发布的扩展点声明。
     *
     * @return 已发布的扩展点声明副本
     */
    public List<SourceExtensionFactory> getPublished() {
        return List.copyOf(published);
    }

    /**
     * 丢弃 ClassLoader 引用并清理编译产物目录，使已编译产物可被回收。
     *
     * <p>停机时调用：留着 class 文件对进程退出没有意义，只会污染下次启动的排查现场。</p>
     */
    public void releaseLoader() {
        currentLoader = null;
        cleanOutput();
    }

    /**
     * 列出源码目录下的全部 {@code .java} 文件。
     *
     * @return 源文件路径列表，按路径排序以保证编译顺序稳定
     */
    private List<Path> listJavaSources() {
        if (!Files.isDirectory(sourceDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(sourceDirectory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取源码目录失败: " + sourceDirectory, e);
        }
    }

    /**
     * 准备本轮的编译输出目录。
     *
     * <p><b>用轮次序号建独立子目录</b>，避免上一轮的 class 残留导致「删掉源码后类还在」。
     * 但轮次目录只新增不清理的话，长期运行会持续堆积——实测 15 轮重载留下 15 个目录、
     * 数百 MB 无主 class 文件。故每轮开始前先清掉上一轮目录。</p>
     *
     * <p>清理时机选在「新建本轮目录之前」而非编译之后：编译失败时也要清，
     * 否则连续写坏源码同样会堆积。</p>
     *
     * @return 本轮输出目录
     */
    private Path prepareOutputDir() {
        try {
            // 先清掉上一轮：轮次目录是「一次性」的，保留它没有任何价值，
            // 只会让磁盘与运维排查越来越糟。boot-inf 是 fat jar 依赖抽取缓存，
            // 跨轮复用，刻意不在清理范围内。
            Path previous = currentRoundDir;
            if (previous != null && Files.exists(previous)) {
                deleteRecursively(previous);
                log.debug("[osgi-source] 已清理上一轮编译产物: {}", previous.getFileName());
            }
            Path dir = outputDirectory.resolve("r" + Long.toHexString(System.nanoTime()));
            Files.createDirectories(dir);
            currentRoundDir = dir;
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("准备编译输出目录失败: " + outputDirectory, e);
        }
    }

    /**
     * 清理本编译器残留的全部轮次目录。
     *
     * <p>供应用停止时调用；{@link #stop()} 已在释放 ClassLoader 前调用。</p>
     */
    public void cleanOutput() {
        try {
            if (Files.isDirectory(outputDirectory)) {
                deleteRecursively(outputDirectory);
                log.info("[osgi-source] 已清理编译产物目录: {}", outputDirectory);
            }
        } catch (IOException e) {
            log.warn("[osgi-source] 清理编译产物目录失败: {}", e.getMessage());
        } finally {
            currentRoundDir = null;
        }
    }

    /**
     * 调用 JDK 编译器编译源码。
     *
     * @param sources    源文件列表
     * @param classesDir 输出目录
     * @return 编译成功返回 {@code true}
     */
    private boolean compile(List<Path> sources, Path classesDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            log.error("[osgi-source] 当前 JVM 无 JavaCompiler（缺少 jdk.compiler 模块或运行于 JRE），无法编译源码");
            return false;
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(toFiles(sources));
            List<String> options = buildCompilerOptions(classesDir);
            boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            logDiagnostics(diagnostics);
            if (!ok) {
                log.error("[osgi-source] 编译失败: {} 个源文件", sources.size());
            }
            return ok;
        } catch (IOException e) {
            log.error("[osgi-source] 编译过程 IO 失败", e);
            return false;
        }
    }

    /**
     * 组装编译器参数。
     *
     * @param classesDir 输出目录
     * @return 编译器参数列表
     */
    private List<String> buildCompilerOptions(Path classesDir) {
        List<String> options = Wraps.list(List.of(
                "-d", classesDir.toString(),
                "-encoding", "UTF-8",
                // 源码目录内的类互相引用时无需 module-info 参与
                "-proc:none"));
        // 关键：把应用 classpath 显式传给编译器，否则 import 应用侧 SPI 接口会报「找不到符号」
        List<String> classpath = resolveClasspath();
        if (!classpath.isEmpty()) {
            options.add("-classpath");
            options.add(String.join(File.pathSeparator, classpath));
        }
        return options;
    }

    /**
     * 解析应用 classpath。
     *
     * <p><b>不能用 {@code java.class.path} 作为唯一来源</b>：Spring Boot fat jar 启动时
     * 该属性<b>非空但只有 jar 自身</b>，全部依赖在 {@code BOOT-INF/lib} 里由
     * LaunchedURLClassLoader 自行加载。若直接返回它的拆分结果，源码 import 任何
     * 应用类都会报「程序包不存在」——实测 eduai 首次部署即因此全量编译失败。</p>
     *
     * <p>因此以「遍历 ClassLoader 的 classpath URL」为主来源，
     * {@code java.class.path} 仅作补充：</p>
     * <ul>
     *   <li>普通 {@code -jar} 启动：ClassLoader URL 就是完整的 classpath，正确</li>
     *   <li>IDE / Maven surefire：ClassLoader 可能不给 URL，此时靠系统属性兜底，正确</li>
     * </ul>
     *
     * @return classpath 条目列表
     */
    private List<String> resolveClasspath() {
        List<String> entries = Wraps.list();
        // 1) 优先从 ClassLoader 取：fat jar 场景下这是唯一能拿到 BOOT-INF/lib 的途径
        ClassLoader loader = applicationClassLoader;
        while (loader != null) {
            if (loader instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) {
                    addIfAbsent(entries, toClasspathEntry(url));
                }
            }
            loader = loader.getParent();
        }
        // 2) 系统属性兜底：IDE 与 surefire 下 ClassLoader 可能不暴露 URL
        String property = System.getProperty("java.class.path", "");
        if (!property.isBlank()) {
            for (String item : property.split(File.pathSeparator)) {
                addIfAbsent(entries, item);
            }
        }
        if (entries.isEmpty()) {
            log.warn("[osgi-source] 未能解析任何 classpath 条目，源码将无法引用应用类；"
                    + "当前 ClassLoader={}", applicationClassLoader);
        } else {
            log.debug("[osgi-source] 已解析 {} 条 classpath 条目", entries.size());
        }
        return entries;
    }

    /**
     * 把 ClassLoader 的 URL 转成 javac 可用的 classpath 条目。
     *
     * <p><b>Spring Boot fat jar 的坑（实测 198 上 eduai）</b>：
     * {@code LaunchedClassLoader} 暴露 400+ 个 URL，但协议是
     * {@code jar:nested:/path/app.jar/!BOOT-INF/lib/xxx.jar!/} 而非 {@code file}。
     * javac 不认这种协议，只按「协议 + 路径」拼 {@code -classpath}，
     * 因此必须还原成它能读的真实文件位置。</p>
     *
     * <p>两种还原方式：</p>
     * <ul>
     *   <li>{@code BOOT-INF/classes} → 解包到临时目录，追加该目录（源码要能引用应用自身类）</li>
     *   <li>{@code BOOT-INF/lib/*.jar} → <b>逐个从外层 jar 抽出到临时目录</b>，
     *       否则 javac 读不到依赖里的类。抽取有缓存：只在首次编译时做一次</li>
     * </ul>
     *
     * @param url ClassLoader 暴露的 URL
     * @return javac 可用的路径；无法还原时返回 {@code null}
     */
    private String toClasspathEntry(URL url) {
        String protocol = url.getProtocol();
        if ("file".equals(protocol)) {
            return decodePath(url.getPath());
        }
        if (!NESTED_JAR_PROTOCOL.equals(protocol)) {
            return null;
        }
        // jar:nested:/home/.../app.jar/!BOOT-INF/lib/xxx.jar!/
        String spec = url.toString();
        int marker = spec.indexOf(NESTED_MARKER);
        if (marker < 0) {
            return null;
        }
        String outerPath = spec.substring("jar:nested:".length(), marker);
        // 标记前紧邻的是外层 jar 路径，可能带一个尾部分隔斜杠（实测
        // "jar:nested:/path/app.jar/!BOOT-INF/..."），不裁掉会让 isRegularFile 判否
        while (outerPath.endsWith("/")) {
            outerPath = outerPath.substring(0, outerPath.length() - 1);
        }
        String inner = spec.substring(marker + 1);
        if (inner.endsWith(NESTED_TAIL)) {
            inner = inner.substring(0, inner.length() - NESTED_TAIL.length());
        }
        if (inner.startsWith("BOOT-INF/classes")) {
            return extractNested(outerPath, null, true);
        }
        if (inner.startsWith("BOOT-INF/lib/")) {
            String entry = inner.substring("BOOT-INF/lib/".length());
            if (!entry.endsWith(".jar")) {
                return null;
            }
            return extractNested(outerPath, entry, false);
        }
        return null;
    }

    /**
     * 从 fat jar 中抽出嵌套条目到缓存目录。
     *
     * @param outerPath 外层 jar 的文件系统路径
     * @param entryName  嵌套条目名（如 {@code commons-lang3-3.20.0.jar}）；为 {@code null} 表示解包 classes 目录
     * @param directory 是否为目录
     * @return 抽出的文件/目录路径；失败返回 {@code null}
     */
    private String extractNested(String outerPath, String entryName, boolean directory) {
        Path outer = Path.of(outerPath);
        if (!Files.isRegularFile(outer)) {
            return null;
        }
        Path cacheRoot = outputDirectory.resolve("boot-inf");
        try {
            if (directory) {
                Path target = cacheRoot.resolve("classes");
                if (!Files.exists(target)) {
                    copyDirectoryEntry(outer, "BOOT-INF/classes", target);
                }
                return target.toString();
            }
            Path target = cacheRoot.resolve("lib").resolve(entryName);
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                copyJarEntry(outer, "BOOT-INF/lib/" + entryName, target);
            }
            return target.toString();
        } catch (IOException e) {
            log.debug("[osgi-source] 抽取嵌套条目失败 {}: {}", entryName, e.getMessage());
            return null;
        }
    }

    /**
     * 从 jar 中复制单个文件条目。
     *
     * @param outer  外层 jar 路径
     * @param entry  jar 内条目名
     * @param target 目标文件
     * @throws IOException 复制失败
     */
    private void copyJarEntry(Path outer, String entry, Path target) throws IOException {
        try (JarFile jarFile = new JarFile(outer.toFile())) {
            JarEntry jarEntry = jarFile.getJarEntry(entry);
            if (jarEntry == null) {
                return;
            }
            try (var input = jarFile.getInputStream(jarEntry)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * 从 jar 中复制整个目录条目。
     *
     * @param outer    外层 jar 路径
     * @param prefix   目录前缀
     * @param target   目标目录
     * @throws IOException 复制失败
     */
    private void copyDirectoryEntry(Path outer, String prefix, Path target) throws IOException {
        Files.createDirectories(target);
        try (JarFile jarFile = new JarFile(outer.toFile())) {
            String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(normalized)) {
                    continue;
                }
                Path relative = Path.of(entry.getName().substring(normalized.length()));
                Path destination = target.resolve(relative.toString());
                Files.createDirectories(destination.getParent());
                try (var input = jarFile.getInputStream(entry)) {
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * URL 解码（路径中的空格等会被编码）。
     *
     * @param path 编码后的路径
     * @return 解码结果，解码失败时返回原值
     */
    private static String decodePath(String path) {
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    /**
     * 追加 classpath 条目并去重。
     *
     * @param entries 条目列表
     * @param item    待追加条目
     */
    private static void addIfAbsent(List<String> entries, String item) {
        if (item != null && !item.isBlank() && !entries.contains(item)) {
            entries.add(item);
        }
    }

    /**
     * 输出编译诊断。
     *
     * @param diagnostics 诊断收集器
     */
    private void logDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        for (Diagnostic<? extends JavaFileObject> item : diagnostics.getDiagnostics()) {
            String message = String.format("[osgi-source] %s:%d %s - %s",
                    item.getSource() == null ? "?" : item.getSource().getName(),
                    item.getLineNumber(),
                    item.getKind().name(),
                    item.getMessage(null));
            if (item.getKind() == Diagnostic.Kind.ERROR) {
                log.error(message);
            } else {
                log.warn(message);
            }
        }
    }

    /**
     * 扫描编译产物，实例化全部 {@link SourceSpi} 宿主类并收集其扩展点。
     *
     * <p>单个宿主类失败只跳过该类，不影响其余——一个源码文件写错不至于
     * 让整批扩展点都不可用。</p>
     *
     * @param classesDir 编译产物目录
     * @param loader     本轮 ClassLoader
     * @param batch      收集结果
     * @throws IOException             目录遍历失败
     * @throws ClassNotFoundException  类加载失败
     * @throws ReflectiveOperationException 反射实例化失败
     */
    private void collectDeclarations(Path classesDir, ClassLoader loader,
                                     List<SourceExtensionFactory> batch)
            throws IOException, ClassNotFoundException, ReflectiveOperationException {
        List<String> classNames = listCompiledClasses(classesDir);
        for (String className : classNames) {
            Class<?> clazz = Class.forName(className, false, loader);
            if (!clazz.isAnnotationPresent(SourceSpi.class) || clazz.isInterface()) {
                continue;
            }
            collectFromHostClass(clazz, loader, batch);
        }
    }

    /**
     * 从单个宿主类收集全部 public 字段对应的扩展点声明。
     *
     * <p>此处<b>不立即求值</b>字段，而是把「宿主实例 + 字段」打包成延迟工厂：
     * 实例化推迟到 {@link SourceExtensionResolver} 真正注册时，
     * 这样同一批声明可安全地被多次 resolve（例如不同 ClassLoader 各来一次），
     * 不会因首次求值后宿主对象失效而注册到空实现。</p>
     *
     * <p>单个字段求值失败只跳过该字段，不影响其余。</p>
     *
     * @param clazz 宿主类
     * @param loader 本轮 ClassLoader，工厂据此取回宿主实例
     * @param batch  收集结果
     * @throws ReflectiveOperationException 反射实例化失败
     */
    private void collectFromHostClass(Class<?> clazz, ClassLoader loader,
                                      List<SourceExtensionFactory> batch)
            throws ReflectiveOperationException {
        Object host;
        try {
            host = clazz.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            log.warn("[osgi-source] 跳过 {}: 缺少 public 无参构造器", clazz.getName());
            return;
        }
        // getDeclaredFields 与 getFields 的交集是同一批字段，直接相加会让每个扩展点被收两遍，
        // 注册出同名两份定义（getExtension 只取到其中一份，另一份成为永不回收的垃圾）
        Map<String, Field> byName = Wraps.linkedMap();
        for (Field field : clazz.getDeclaredFields()) {
            byName.put(field.getName(), field);
        }
        for (Field field : clazz.getFields()) {
            byName.putIfAbsent(field.getName(), field);
        }
        int before = batch.size();
        for (Field field : byName.values()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)
                    || field.isSynthetic() || field.getType().isPrimitive()) {
                continue;
            }
            final Field target = field;
            batch.add(new SourceExtensionFactory() {

                @Override
                public Object create() {
                    try {
                        Object value = target.get(host);
                        if (value == null) {
                            log.warn("[osgi-source] 跳过 {}.{}: 字段值为 null",
                                    clazz.getSimpleName(), target.getName());
                        }
                        return value;
                    } catch (IllegalAccessException e) {
                        log.warn("[osgi-source] 跳过 {}.{}: 字段不可读",
                                clazz.getSimpleName(), target.getName());
                        return null;
                    }
                }

                @Override
                public Class<?> spiType() {
                    return target.getType();
                }

                @Override
                public String extensionName() {
                    return target.getName();
                }

                @Override
                public String toString() {
                    return clazz.getSimpleName() + "." + target.getName();
                }
            });
        }
        log.info("[osgi-source] 宿主类 {} 贡献 {} 个扩展点", clazz.getName(), batch.size() - before);
    }

    /**
     * 列出编译产物中的全部类名。
     *
     * @param classesDir 编译产物目录
     * @return 点分类名列表
     * @throws IOException 目录遍历失败
     */
    private List<String> listCompiledClasses(Path classesDir) throws IOException {
        List<String> names = Wraps.list();
        Path root = classesDir.toAbsolutePath();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString();
                if (relative.endsWith(".class")) {
                    names.add(relative.substring(0, relative.length() - ".class".length())
                            .replace(File.separatorChar, '.'));
                }
            }
        }
        names.sort(null);
        return names;
    }

    /**
     * 递归删除目录。
     *
     * @param path 目录路径
     * @throws IOException 删除失败
     */
    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> entries = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }

    /**
     * 取本轮 ClassLoader。
     *
     * @return ClassLoader，尚未编译时为 {@code null}
     */
    public ClassLoader getCurrentLoader() {
        return currentLoader;
    }

    /**
     * 取源码目录。
     *
     * @return 源码目录
     */
    public Path getSourceDirectory() {
        return sourceDirectory;
    }

    /**
     * 取编译产物输出目录。
     *
     * @return 输出目录
     */
    public Path getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * 路径列表转文件列表。
     *
     * @param paths 路径列表
     * @return 文件列表
     */
    private static List<File> toFiles(List<Path> paths) {
        return paths.stream().map(Path::toFile).toList();
    }

    /**
     * 承载源码编译产物的 ClassLoader。
     *
     * <p>以编译输出目录为唯一 classpath 条目，父加载器为应用 ClassLoader，
     * 因此源码既能引用自身编译的类，也能引用应用侧已有的 SPI 接口。</p>
     */
    private static final class SourceClassLoader extends URLClassLoader {

        /**
         * 创建源码 ClassLoader。
         *
         * @param classesDir 编译产物目录
         * @param parent     父加载器
         * @throws MalformedURLException 编译产物目录无法转成 URL
         */
        private SourceClassLoader(Path classesDir, ClassLoader parent) throws MalformedURLException {
            super(new URL[]{classesDir.toUri().toURL()}, parent);
        }
    }
}
