package com.chua.osgi.support.source;

import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 源码扩展点热重载真实测试。
 *
 * <p>全部使用<b>真实</b> JDK 编译器与<b>真实</b> SPI 注册表，不做打桩——
 * 本组件的价值全在「编译产物是否真的进了 SPI 表、旧的是否真的被摘掉」，
 * 打桩后这些断言全部失去意义。</p>
 *
 * <p>固化的是实现过程中真实踩到并修正的三个坑：</p>
 * <ol>
 *   <li><b>finderType 必须填解析器类自身</b>（不是业务 SPI 接口）。注销判据是
 *       {@code resolverType.isAssignableFrom(finderType)}，两者毫无继承关系时恒为 false，
 *       扩展点永远摘不掉——表现为「热重载后新代码不生效」且<b>不报任何错</b></li>
 *   <li><b>注册表里不得有同名两份定义</b>：{@code register} 是 add 语义，
 *       收集字段时若把 {@code getDeclaredFields()} 与 {@code getFields()} 直接相加，
 *       同一字段会被收两遍</li>
 *   <li><b>编译失败必须保留上一轮</b>，而不是清空——源码写错是最常见操作失误</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.43
 */
class SourceExtensionSchedulerRealTest {

    /**
     * 源码里可能出现的编译错误样例。
     */
    private static final String BROKEN_SOURCE = """
            package demo;
            public class Broken {
                public void x() { this is not java }
            }
            """;

    /**
     * 构造一份合法的宿主类源码。
     *
     * @param packageName 包名
     * @param className   类名
     * @param fieldName   扩展名字段名
     * @param greeting    问候语内容
     * @return 源码文本
     */
    private static String hostSource(String packageName, String className,
                                     String fieldName, String greeting) {
        return """
                package %s;

                import com.chua.osgi.support.source.SourceSpi;
                import com.chua.osgi.support.source.TestGreeter;

                @SourceSpi
                public class %s {
                    public final TestGreeter %s = name -> "%s";
                }
                """.formatted(packageName, className, fieldName, greeting);
    }

    /**
     * 写出源码文件。
     *
     * @param dir  目录
     * @param name 文件名
     * @param body 内容
     * @return 写出的文件路径
     * @throws IOException 写入失败
     */
    private static Path write(Path dir, String name, String body) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 创建调度器（不启动轮询，由测试手动触发）。
     *
     * @param sourceDir 源码目录
     * @param outputDir 输出目录
     * @return 调度器
     */
    private static SourceExtensionScheduler newScheduler(Path sourceDir, Path outputDir) {
        var compiler = new SourceJavaCompiler(sourceDir, outputDir, null);
        return new SourceExtensionScheduler(compiler, null, 0L);
    }

    /**
     * 源码编译出的扩展点能被 SPI 查到，且拿到的是源码里的实现。
     */
    @Test
    @DisplayName("源码编译产物注册后可由 SPI 按名取到")
    void compiledSource_shouldBeResolvableBySpi(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Files.createDirectories(sourceDir);
        write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "你好"));

        var scheduler = newScheduler(sourceDir, temp.resolve("out"));
        try {
            assertTrue(scheduler.reloadIfChanged(), "首次加载应发生编译");
            TestGreeter greeter = ServiceProvider.of(TestGreeter.class).getExtension("hello");
            assertNotNull(greeter, "源码里的 hello 扩展点应已注册");
            assertEquals("你好", greeter.greet("学生"));
        } finally {
            scheduler.stop();
        }
    }

    /**
     * 热重载后旧实例必须被真正替换，而不是同名堆积。
     *
     * <p>若注销不生效，{@code getExtension} 会仍返回旧实例，
     * 表现为「改了代码不生效」——且不报任何错。</p>
     */
    @Test
    @DisplayName("热重载后取到的是新实例，旧实例被替换而非堆积")
    void reload_shouldReplacePreviousInstance(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Files.createDirectories(sourceDir);
        write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "第一版"));

        var scheduler = newScheduler(sourceDir, temp.resolve("out"));
        try {
            scheduler.reloadIfChanged();
            TestGreeter first = ServiceProvider.of(TestGreeter.class).getExtension("hello");
            assertNotNull(first, "首次应注册成功");
            assertEquals("第一版", first.greet("x"), "首次应取到第一版实现");

            write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "第二版"));
            scheduler.reloadIfChanged();

            TestGreeter second = ServiceProvider.of(TestGreeter.class).getExtension("hello");
            assertEquals("第二版", second.greet("x"), "重载后应取到第二版实现");
            assertFalse(first == second, "新旧实例不应是同一个对象");
        } finally {
            scheduler.stop();
        }
    }

    /**
     * 源码里删掉的扩展点，删除后必须随之消失（替换而非合并语义）。
     */
    @Test
    @DisplayName("源码删除扩展点后，SPI 中随之消失")
    void removedExtension_shouldDisappearAfterReload(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Files.createDirectories(sourceDir);
        write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "第一版"));

        var scheduler = newScheduler(sourceDir, temp.resolve("out"));
        try {
            scheduler.reloadIfChanged();
            assertNotNull(ServiceProvider.of(TestGreeter.class).getExtension("hello"));

            write(sourceDir, "Hello.java", """
                    package demo;

                    import com.chua.osgi.support.source.SourceSpi;

                    @SourceSpi
                    public class Hello {
                    }
                    """);
            scheduler.reloadIfChanged();

            assertNull(ServiceProvider.of(TestGreeter.class).getExtension("hello"),
                    "源码已无 hello 字段，扩展点应随之消失");
        } finally {
            scheduler.stop();
        }
    }

    /**
     * 编译失败必须保留上一轮扩展点，而不是清空。
     */
    @Test
    @DisplayName("编译失败时保留上一轮扩展点，不清空")
    void brokenSource_shouldKeepPreviousExtensions(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Files.createDirectories(sourceDir);
        write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "可用版"));

        var scheduler = newScheduler(sourceDir, temp.resolve("out"));
        try {
            scheduler.reloadIfChanged();
            assertNotNull(ServiceProvider.of(TestGreeter.class).getExtension("hello"));

            write(sourceDir, "Broken.java", BROKEN_SOURCE);
            scheduler.reloadIfChanged();

            TestGreeter after = ServiceProvider.of(TestGreeter.class).getExtension("hello");
            assertNotNull(after, "编译失败不应清空已注册扩展点");
            assertEquals("可用版", after.greet("x"), "应仍是上一版实现");
        } finally {
            scheduler.stop();
        }
    }

    /**
     * 单个宿主类的多个字段各自注册为独立扩展点，且不重复收集。
     */
    @Test
    @DisplayName("单个宿主类的多个字段各自注册且每项只有一份定义")
    void multipleFields_shouldRegisterIndependently(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Files.createDirectories(sourceDir);
        write(sourceDir, "Many.java", """
                package demo;

                import com.chua.osgi.support.source.SourceSpi;
                import com.chua.osgi.support.source.TestGreeter;

                @SourceSpi
                public class Many {
                    public final TestGreeter formal = name -> "formal:" + name;
                    public final TestGreeter casual = name -> "casual:" + name;
                }
                """);

        var scheduler = newScheduler(sourceDir, temp.resolve("out"));
        try {
            scheduler.reloadIfChanged();
            var provider = ServiceProvider.of(TestGreeter.class);
            assertEquals("formal:学生", provider.getExtension("formal").greet("学生"));
            assertEquals("casual:学生", provider.getExtension("casual").greet("学生"));
            // 两个字段应恰好产出两条声明。收集字段时若把 getDeclaredFields() 与
            // getFields() 直接相加，同一字段会被收两遍，注册表里就多出同名定义，
            // 其中一份永远取不到、也永远不会被回收。
            var compiler = new SourceJavaCompiler(sourceDir, temp.resolve("out2"), null);
            assertEquals(2, compiler.compile().size(), "两个字段应恰好产出两条声明，不应重复收集");
        } finally {
            scheduler.stop();
        }
    }

    /**
     * 停机后不得残留指向源码 ClassLoader 的扩展点，否则会阻止类加载器回收。
     */
    @Test
    @DisplayName("stop 后扩展点被注销，不残留源码引用")
    void stop_shouldUnregisterExtensions(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Files.createDirectories(sourceDir);
        write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "临时"));

        var scheduler = newScheduler(sourceDir, temp.resolve("out"));
        scheduler.reloadIfChanged();
        assertNotNull(ServiceProvider.of(TestGreeter.class).getExtension("hello"));

        scheduler.stop();

        assertNull(ServiceProvider.of(TestGreeter.class).getExtension("hello"),
                "stop 应注销源码扩展点，避免 ClassLoader 无法回收");
    }

    /**
     * 未变更时不重复编译。
     */
    @Test
    @DisplayName("源码未变更时不触发重编译")
    void unchangedSource_shouldNotRecompile(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Files.createDirectories(sourceDir);
        write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "稳定版"));

        var scheduler = newScheduler(sourceDir, temp.resolve("out"));
        try {
            assertTrue(scheduler.reloadIfChanged(), "首次应编译");
            assertFalse(scheduler.reloadIfChanged(), "未变更时不应再编译");
            assertFalse(scheduler.reloadIfChanged(), "未变更时不应再编译");
            assertEquals("稳定版", ServiceProvider.of(TestGreeter.class).getExtension("hello").greet("x"));
        } finally {
            scheduler.stop();
        }
    }

    /**
     * 空目录（配置先开、源码后放）不应报错。
     */
    @Test
    @DisplayName("源码目录不存在时按无源码处理，不抛异常")
    void missingDirectory_shouldBeTolerated(@TempDir Path temp) {
        var scheduler = newScheduler(temp.resolve("not-exist"), temp.resolve("out"));
        try {
            scheduler.reloadIfChanged();
            assertTrue(true, "目录不存在时应静默跳过");
        } finally {
            scheduler.stop();
        }
    }

    /**
     * 解析器对未声明的 SPI 类型返回空列表，而不是抛异常。
     */
    @Test
    @DisplayName("解析器对未声明类型返回空列表")
    void resolver_shouldReturnEmptyForUnknownType() {
        var resolver = new SourceExtensionResolver();
        resolver.replaceAll(List.of());
        assertEquals(List.of(), resolver.resolve(TestGreeter.class, getClass().getClassLoader()));
        assertTrue(resolver.isDynamic(), "源码扩展点属动态解析器");
    }

    /**
     * 源码用容器 Bean 构造扩展点：上下文已安装时才能拿到非空字段。
     *
     * <p><b>固化的是一条真实踩过的时序约束</b>：源码产物不在容器内，
     * 只能通过 {@link SourceContextHolder} 取 Bean。首次部署 eduai 时，
     * 装配顺序是「先 start() 再 install()」，而 start() 以 0 延迟立即跑首轮编译，
     * 于是源码取 Mapper 得到 null、扩展点字段被跳过——日志显示
     * 「编译完成，产出 1 个扩展点」但同时「跳过 student: 字段值为 null」，
     * <b>登录面静默失效且不报错</b>。修正后为「先 install 再 start」。</p>
     */
    @Test
    @DisplayName("上下文已安装时源码能取到容器 Bean 并构造出非空扩展点")
    void sourceExtension_shouldGetBeanFromInstalledContext(@TempDir Path temp) throws IOException {
        var repo = (TestGreeter) name -> "容器:" + name;
        SourceContextHolder.install(java.util.Map.of("testGreeter", repo));

        Path sourceDir = temp.resolve("src");
        Files.createDirectories(sourceDir);
        write(sourceDir, "WithBean.java", """
                package demo;

                import com.chua.osgi.support.source.SourceContextHolder;
                import com.chua.osgi.support.source.SourceSpi;
                import com.chua.osgi.support.source.TestGreeter;

                @SourceSpi
                public class WithBean {
                    public final TestGreeter ctxBean = SourceContextHolder.get(TestGreeter.class);
                }
                """);

        var compiler = new SourceJavaCompiler(sourceDir, temp.resolve("out"), null);
        var batch = compiler.compile();
        assertEquals(1, batch.size(), "应产出一条声明");
        assertNotNull(batch.get(0).create(),
                "上下文已安装，源码不应拿到 null —— 若此处为 null 说明装配顺序又倒退了");
    }

    /**
     * 上下文未安装时源码字段为 null，扩展点被跳过而非抛异常。
     *
     * <p>这是刻意设计：拿不到容器 Bean 时降级为「不注册该扩展点」，
     * 由 oauth 侧回落到内置凭据逻辑，而不是把登录面打挂。</p>
     */
    @Test
    @DisplayName("上下文未安装时字段为 null，扩展点被跳过而非抛异常")
    void sourceExtension_shouldDegradeWhenContextAbsent(@TempDir Path temp) throws IOException {
        SourceContextHolder.clear();

        Path sourceDir = temp.resolve("src");
        Files.createDirectories(sourceDir);
        write(sourceDir, "WithBean.java", """
                package demo;

                import com.chua.osgi.support.source.SourceContextHolder;
                import com.chua.osgi.support.source.SourceSpi;
                import com.chua.osgi.support.source.TestGreeter;

                @SourceSpi
                public class WithBean {
                    public final TestGreeter ctxBean = SourceContextHolder.get(TestGreeter.class);
                }
                """);

        var compiler = new SourceJavaCompiler(sourceDir, temp.resolve("out"), null);
        var batch = compiler.compile();
        assertEquals(1, batch.size(), "声明仍会产出（字段为 null 只是实例为 null）");
        assertNull(batch.get(0).create(), "上下文未安装时应为 null，交由注册侧跳过");
    }

    /**
     * 轮次编译目录必须逐轮清理，否则长期运行会持续堆积无主 class 文件。
     *
     * <p><b>固化的是实测缺陷</b>：早先版本每轮只新建 {@code r<hex>} 目录、
     * 从不清理，实测 15 轮重载后留下 15 个目录、磁盘持续增长。
     * 生产上源码热重载是长期运行的功能，这类泄漏必然演变成磁盘写满。</p>
     */
    @Test
    @DisplayName("多轮编译后只保留最新一轮的产物目录")
    void consecutiveCompiles_shouldNotAccumulateRoundDirs(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Path outDir = temp.resolve("out");
        Files.createDirectories(sourceDir);
        var compiler = new SourceJavaCompiler(sourceDir, outDir, null);

        for (int i = 0; i < 5; i++) {
            // 每轮改内容，确保指纹变化、强制重编译
            write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "第" + i + "版"));
            var batch = compiler.compile();
            assertEquals(1, batch.size(), "第 " + i + " 轮应产出 1 条声明");
        }

        long roundDirs;
        try (var stream = Files.list(outDir)) {
            roundDirs = stream.filter(p -> p.getFileName().toString().startsWith("r")).count();
        }
        assertEquals(1, roundDirs,
                "5 轮编译后应只剩 1 个轮次目录，实际残留 " + roundDirs + " 个（磁盘泄漏）");
    }

    /**
     * 连续编译失败也不应堆积目录。
     *
     * <p>源码写错是最常见的现场，若失败轮次留下目录，运维反复改错源码就会
     * 把磁盘写满——而这恰恰是最需要服务保持可用的时刻。</p>
     */
    @Test
    @DisplayName("连续编译失败也不堆积轮次目录")
    void consecutiveFailures_shouldNotAccumulateRoundDirs(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Path outDir = temp.resolve("out");
        Files.createDirectories(sourceDir);
        write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "可用版"));
        var compiler = new SourceJavaCompiler(sourceDir, outDir, null);
        compiler.compile();

        // 连续 3 轮编译失败
        for (int i = 0; i < 3; i++) {
            write(sourceDir, "Hello.java", """
                    package demo;

                    import com.chua.osgi.support.source.SourceSpi;
                    import com.chua.osgi.support.source.TestGreeter;

                    @SourceSpi
                    public class Hello {
                        public final TestGreeter hello = name -> "v""" + i + """
                        ;
                    }
                    """);
            var outcome = compiler.compileWithOutcome();
            assertFalse(outcome.success(), "第 " + i + " 轮应编译失败");
        }

        long roundDirs;
        try (var stream = Files.list(outDir)) {
            roundDirs = stream.filter(p -> p.getFileName().toString().startsWith("r")).count();
        }
        assertEquals(1, roundDirs, "失败轮次同样不应堆积目录，实际 " + roundDirs + " 个");
    }

    /**
     * 停机清理：编译产物目录整体删除，不留垃圾。
     */
    @Test
    @DisplayName("releaseLoader 后编译产物目录被清理")
    void releaseLoader_shouldCleanOutputDirectory(@TempDir Path temp) throws IOException {
        Path sourceDir = temp.resolve("src");
        Path outDir = temp.resolve("out");
        Files.createDirectories(sourceDir);
        write(sourceDir, "Hello.java", hostSource("demo", "Hello", "hello", "临时"));
        var compiler = new SourceJavaCompiler(sourceDir, outDir, null);
        compiler.compile();
        assertTrue(Files.isDirectory(outDir));

        compiler.releaseLoader();

        assertFalse(Files.exists(outDir), "停机应清理编译产物目录，避免污染排查现场");
    }

    /**
     * classpath 解析必须能覆盖 Spring Boot fat jar 场景。
     *
     * <p><b>为什么这条单独固化</b>：fat jar 启动时 {@code java.class.path}
     * <b>非空但只有 jar 自身</b>，全部依赖在 {@code BOOT-INF/lib} 里由
     * {@code LaunchedClassLoader} 加载。只用系统属性拼 classpath 会让源码
     * import 任何应用类都报「程序包不存在」——eduai 首次部署即因此全量编译失败。
     * 本用例断言 classpath 里除 jar 外还应包含依赖项。</p>
     */
    @Test
    @DisplayName("classpath 除自身 jar 外还应包含依赖项（fat jar 场景）")
    void classpath_shouldIncludeMoreThanLauncherJar(@TempDir Path temp) throws Exception {
        var compiler = new SourceJavaCompiler(temp.resolve("src"), temp.resolve("out"), null);
        var method = SourceJavaCompiler.class.getDeclaredMethod("resolveClasspath");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var entries = (List<String>) method.invoke(compiler);
        assertFalse(entries.isEmpty(), "应至少解析出启动 classpath");
        // surefire 下 java.class.path 是完整依赖列表，能编译出引用应用类的源码
        assertTrue(entries.size() > 1,
                "classpath 应包含依赖项而非只有启动项，实际: " + entries.size() + " 条");
    }

    /**
     * 显式固化「finderType 必须填解析器类」这条 SPI 约定。
     *
     * <p>若将来有人把 finderType 改回业务接口类型，本用例会立刻失败。</p>
     */
    @Test
    @DisplayName("定义的 finderType 必须是解析器类自身，否则注销无法命中")
    void finderType_shouldBeResolverClass() {
        var factory = new SourceExtensionFactory() {

            @Override
            public Object create() {
                return (TestGreeter) name -> "x";
            }

            @Override
            public Class<?> spiType() {
                return TestGreeter.class;
            }

            @Override
            public String extensionName() {
                return "probe";
            }
        };
        var resolver = new SourceExtensionResolver();
        resolver.replaceAll(List.of(factory));
        var definitions = resolver.resolve(TestGreeter.class, getClass().getClassLoader());
        assertEquals(1, definitions.size());
        assertSame(SourceExtensionResolver.class, definitions.get(0).getFinderType(),
                "finderType 必须是解析器类自身");
        assertNotNull(definitions.get(0).getImplClass(),
                "implClass 必须设置，否则 ConditionEvaluator 会静默丢弃该定义");
    }
}
