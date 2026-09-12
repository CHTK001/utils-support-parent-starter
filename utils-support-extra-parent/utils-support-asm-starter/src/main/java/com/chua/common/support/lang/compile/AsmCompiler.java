package com.chua.common.support.lang.compile;

import com.chua.common.support.spi.annotations.Spi;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
* ASM 动态编译器实现
*
* 该实现通过 JDK 编译器 API 将 Java 源码编译为字节码，再使用 ASM 对字节码进行二次处理，
* 确保生成的字节码包含完整的 stack映射table 帧信息，提升运行时类加载的兼容性。
* 与 jdkcompiler 的区别在于增加了 ASM 字节码后处理环节。
*
* @author CH
* @since 4.0.0
 */
@Spi("asm")
public class AsmCompiler implements Compiler {

    @Override
    public Class<?> doCompile(String name, String source) throws Throwable {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available");
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager stdFileManager = compiler.getStandardFileManager(null, null, null);

        InMemoryClassLoader classLoader = new InMemoryClassLoader(Thread.currentThread().getContextClassLoader());
        InMemoryFileManager fileManager = new InMemoryFileManager(stdFileManager, classLoader);

        List<JavaFileObject> sources = new ArrayList<>();
        sources.add(new StringSource(name, source));

        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, collector, null, null, sources);
        boolean success = task.call();

        if (!success) {
            StringBuilder sb = new StringBuilder("Compilation failed");
            for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    sb.append("\n  ").append(d.getMessage(null));
                }
            }
            throw new IllegalStateException(sb.toString());
        }

        Map<String, byte[]> bytecodes = classLoader.getBytecodes();

        Map<String, Class<?>> result = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
            String clsName = entry.getKey();
            byte[] raw = entry.getValue();

            ClassReader cr = new ClassReader(raw);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            cr.accept(cw, 0);
            byte[] processed = cw.toByteArray();

            Class<?> clazz = classLoader.defineClass(clsName, processed);
            result.put(clsName, clazz);
        }

        if (result.isEmpty()) {
            return null;
        }
        return result.values().iterator().next();
    }

    /**
    * 字符串源文件对象，将 Java 源码字符串包装为 java文件对象
    * @author CH
    * @since 4.0.0
     */
    static class StringSource extends SimpleJavaFileObject {
        /** 代码 */
        private final String code;

        StringSource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        /** 获取char内容 */
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /**
    * 内存字节码对象，将编译后的 .类 字节码保存在内存中
    * @author CH
    * @since 4.0.0
     */
    static class InMemoryByteCode extends SimpleJavaFileObject {
        /** 字节数组输出流 */
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        /** 类名称 */
        private final String className;

        InMemoryByteCode(String className) {
            super(URI.create("byte:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
            this.className = className;
        }

        String getClassName() {
            return className;
        }

        byte[] getBytes() {
            return baos.toByteArray();
        }

        @Override
        /** 打开输出流 */
        public OutputStream openOutputStream() {
            return baos;
        }
    }

    /**
    * 内存类加载器，负责将内存中的字节码定义为 类 对象
    * @author CH
    * @since 4.0.0
     */
    static class InMemoryClassLoader extends ClassLoader {
        /** 字节码缓存映射 */
        private final Map<String, InMemoryByteCode> bytecodes = new HashMap<>();

        InMemoryClassLoader(ClassLoader parent) {
            super(parent);
        }

        void register(InMemoryByteCode bc) {
            bytecodes.put(bc.getClassName(), bc);
        }

        Map<String, byte[]> getBytecodes() {
            Map<String, byte[]> result = new HashMap<>();
            for (Map.Entry<String, InMemoryByteCode> e : bytecodes.entrySet()) {
                result.put(e.getKey(), e.getValue().getBytes());
            }
            return result;
        }

        Class<?> defineClass(String name, byte[] b) {
            return super.defineClass(name, b, 0, b.length);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            InMemoryByteCode bc = bytecodes.get(name);
            if (bc != null) {
                byte[] b = bc.getBytes();
                return defineClass(name, b, 0, b.length);
            }
            return super.findClass(name);
        }
    }

    /**
    * 内存文件管理器，将编译器输出的字节码重定向到内存而非磁盘文件
    * @author CH
    * @since 4.0.0
     */
    static class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        /** 类加载器 */
        private final InMemoryClassLoader classLoader;

        InMemoryFileManager(JavaFileManager fileManager, InMemoryClassLoader classLoader) {
            super(fileManager);
            this.classLoader = classLoader;
        }

        @Override
        /** 获取类加载 */
        public ClassLoader getClassLoader(Location location) {
            return classLoader;
        }

        @Override
        /**
        * 获取java文件for输出
        * @param location 位置
        * @param className 类名称
        * @param kind 种类
        * @param sibling sibling
         */
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                    JavaFileObject.Kind kind, FileObject sibling) {
            InMemoryByteCode bc = new InMemoryByteCode(className);
            classLoader.register(bc);
            return bc;
        }
    }
}
