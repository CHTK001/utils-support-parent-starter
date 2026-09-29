package com.chua.osgi.support.source;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.script.ScriptProvider;
import com.chua.common.support.task.script.ScriptProviderResolver;
import com.chua.common.support.task.script.ScriptProviders;
import com.chua.groovy.support.script.GroovyScriptProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Groovy 源码扩展点真实测试。
 *
 * <p>{@link SourceGroovyLoader} 复用既有 {@code ScriptProvider} SPI。
 * 生产环境里 {@code GroovyScriptProvider} 由 spring.factories +
 * {@code ScriptProviderSpringRegistrar}（在 spring 层模块）装配，
 * 而本模块是 utils 层、不依赖 spring——故测试直接用
 * {@link ScriptProviderResolver} 注册引擎实例，不为测试引入上层模块。</p>
 *
 * <p>首个用例断言引擎确实可用，避免后续用例「因引擎缺失而假通过」。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
class SourceGroovyLoaderRealTest {

    /**
     * 注册 groovy 脚本引擎，使后续用例能真实走到 Groovy 执行路径。
     */
    @BeforeAll
    static void initScriptProviders() {
        ScriptProviders.useResolver(new ScriptProviderResolver() {

            @Override
            public ScriptProvider resolve(String engine) {
                return "groovy".equals(engine) ? new GroovyScriptProvider() : null;
            }

            @Override
            public Set<String> engines() {
                return Set.of("groovy");
            }
        });
    }

    /**
     * 写出 Groovy 脚本。
     *
     * @param dir  目录
     * @param name 文件名
     * @param body 内容
     * @return 文件路径
     * @throws IOException 写入失败
     */
    private static Path writeScript(Path dir, String name, String body) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 前置条件：groovy 引擎必须真的可用，否则后续用例全是假通过。
     */
    @Test
    @DisplayName("前置条件：groovy ScriptProvider 可被发现")
    void groovyEngine_shouldBeAvailable() {
        assertNotNull(ScriptProviders.resolve("groovy"),
                "groovy 引擎应可用；若失败说明 test 依赖未生效，后续用例会假通过");
    }

    /**
     * 脚本返回 spi/name/value 三元组，应产出可注册的扩展点声明。
     */
    @Test
    @DisplayName("脚本声明的 spi/name/value 被解析为扩展点")
    void scriptDeclaration_shouldBeParsed(@TempDir Path temp) throws IOException {
        writeScript(temp, "ext.groovy", """
                return [
                    spi  : 'com.chua.osgi.support.source.TestGreeter',
                    name : 'fromGroovy',
                    value: { String n -> "groovy:" + n } as com.chua.osgi.support.source.TestGreeter
                ]
                """);

        var loader = new SourceGroovyLoader(temp, "groovy");
        List<SourceExtensionFactory> batch = loader.load();

        assertEquals(1, batch.size(), "应产出 1 条声明");
        SourceExtensionFactory factory = batch.get(0);
        assertEquals(TestGreeter.class, factory.spiType());
        assertEquals("fromGroovy", factory.extensionName());
        TestGreeter greeter = (TestGreeter) factory.create();
        assertEquals("groovy:学生", greeter.greet("学生"));
    }

    /**
     * 脚本返回 List 时应产出多条声明。
     */
    @Test
    @DisplayName("脚本返回 List 时产出多条声明")
    void listDeclaration_shouldProduceMultiple(@TempDir Path temp) throws IOException {
        writeScript(temp, "many.groovy", """
                return [
                    [spi: 'com.chua.osgi.support.source.TestGreeter', name: 'g1',
                     value: ({ n -> 'g1:' + n } as com.chua.osgi.support.source.TestGreeter)],
                    [spi: 'com.chua.osgi.support.source.TestGreeter', name: 'g2',
                     value: ({ n -> 'g2:' + n } as com.chua.osgi.support.source.TestGreeter)]
                ]
                """);

        var loader = new SourceGroovyLoader(temp, "groovy");
        assertEquals(2, loader.load().size(), "应产出 2 条声明");
    }

    /**
     * 端到端：脚本声明经调度器发布后可由 SPI 取到。
     */
    @Test
    @DisplayName("脚本扩展点经调度器发布后可由 SPI 取到")
    void groovyExtension_shouldBeResolvableBySpi(@TempDir Path temp) throws IOException {
        writeScript(temp, "ext.groovy", """
                return [
                    spi  : 'com.chua.osgi.support.source.TestGreeter',
                    name : 'groovySpi',
                    value: { String n -> "hi:" + n } as com.chua.osgi.support.source.TestGreeter
                ]
                """);

        var compiler = new SourceJavaCompiler(temp, temp.resolve("out"), null);
        var loader = new SourceGroovyLoader(temp, "groovy");
        var scheduler = new SourceExtensionScheduler(compiler, loader, 0L);
        try {
            scheduler.reloadIfChanged();
            TestGreeter got = ServiceProvider.of(TestGreeter.class).getExtension("groovySpi");
            assertNotNull(got, "脚本扩展点应已注册");
            assertEquals("hi:学生", got.greet("学生"));
        } finally {
            scheduler.stop();
        }
    }

    /**
     * 引擎不可用时降级为「无声明」而不是抛异常。
     *
     * <p>这是生产环境最常见的形态——本模块生产依赖里没有 groovy。</p>
     */
    @Test
    @DisplayName("引擎不可用时降级为空列表，不抛异常")
    void unavailableEngine_shouldDegradeGracefully(@TempDir Path temp) throws IOException {
        writeScript(temp, "ext.groovy", "return [:]");
        var loader = new SourceGroovyLoader(temp, "no-such-engine");
        assertEquals(List.of(), loader.load(), "引擎缺失时应返回空列表");
    }

    /**
     * 脚本返回值结构不符时跳过并告警，不影响同批其他脚本。
     */
    @Test
    @DisplayName("声明字段缺失时跳过该条，不抛异常")
    void malformedDeclaration_shouldBeSkipped(@TempDir Path temp) throws IOException {
        writeScript(temp, "bad.groovy", "return [foo: 'bar']");
        var loader = new SourceGroovyLoader(temp, "groovy");
        assertEquals(List.of(), loader.load(), "字段缺失应被跳过");
    }
}
