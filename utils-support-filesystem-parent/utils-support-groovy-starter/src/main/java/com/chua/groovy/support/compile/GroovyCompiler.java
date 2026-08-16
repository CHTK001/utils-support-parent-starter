package com.chua.groovy.support.compile;

import com.chua.common.support.lang.compile.Compiler;
import com.chua.common.support.spi.annotations.Spi;
import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;

/**
 * Groovy 动态编译器实现。
 *
 * <p>通过 {@link Compiler} SPI 机制注册为 {@code "groovy"} 编译器，
 * 支持在运行时将 Groovy 源代码字符串动态编译为 Java Class 对象。
 * 内部使用 {@link GroovyClassLoader} 完成编译和类加载。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   Compiler compiler = new GroovyCompiler();
 *   String source = "class Hello {{ def greet() {{ \"Hello, \" + name }} }}";
 *   Class<?> clazz = compiler.doCompile("Hello", source);
 *   Object instance = clazz.getDeclaredConstructor().newInstance();
 * }</pre>
 *
 * <h3>特性说明</h3>
 * <ul>
 *   <li><b>纯内存编译</b> — 编译过程在内存中完成，不产生磁盘文件</li>
 *   <li><b>UTF-8 编码</b> — 源代码默认使用 UTF-8 编码</li>
 *   <li><b>编译配置</b> — 支持通过 {@link CompilerConfiguration} 自定义编译选项</li>
 *   <li><b>兼容性好</b> — 支持标准 Groovy 语法以及 Java 与 Groovy 混编</li>
 *   <li><b>缓存清理</b> — 编译完成后自动清理 GroovyClassLoader 缓存</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>编译后的类使用独立的 {@link GroovyClassLoader} 加载，不会污染应用类加载器</li>
 *   <li>每次编译都会创建新的 ClassLoader，如需重复编译请留意类加载器泄漏</li>
 *   <li>Groovy 源码必须包含完整的类定义（包括 class 关键字和类体）</li>
 *   <li>编译失败时会抛出 {@link CompilationFailedException} 或其子类</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see Compiler
 * @see GroovyClassLoader
 * @see CompilerConfiguration
 */
@Spi("groovy")
public class GroovyCompiler implements Compiler {

    /**
     * 默认的 Groovy 编译器配置。
     * <ul>
     *   <li>源码编码：UTF-8</li>
     * </ul>
     */
    private static final CompilerConfiguration CONFIG = new CompilerConfiguration();

    static {
        CONFIG.setSourceEncoding("UTF-8");
    }

    @Override
    public Class<?> doCompile(String name, String source) throws Throwable {
        // 使用独立的 GroovyClassLoader 编译，避免类加载器污染
        try (GroovyClassLoader groovyClassLoader = new GroovyClassLoader(
                Thread.currentThread().getContextClassLoader(), CONFIG)) {
            try {
                return groovyClassLoader.parseClass(source, name);
            } finally {
                groovyClassLoader.clearCache();
            }
        }
    }
}
