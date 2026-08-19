package com.chua.common.support.lang.compile;

import com.chua.common.support.constant.CommonConstant;
import lombok.extern.slf4j.Slf4j;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.*;
import java.io.*;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;

import static com.chua.common.support.constant.CommonConstant.JAR_URL_SEPARATOR;

// 基于 JDK JavaCompiler API 实现的动态编译器，支持在运行时编译并加载 Java 源代码
/**
 * jdk 动态编译器实现类
 * 利用 Java Compiler API 将字符串形式的 Java 源码编译为 Class 对象
 *
 * @author CHTK
 * @since 4.0.0.42
 */
@Slf4j
public class JdkCompiler implements Compiler {
    /**
     * 执行单次编译任务
     * 将给定的类名和源码编译为 Class 对象并返回
     *
     * @param name   生成的类的全限定名 (例如: com.example.MyClass)
     * @param source Java 源代码字符串
     * @return 编译后的 Class 对象，如果编译失败或无结果则返回 null
     * @throws Throwable 编译过程中可能抛出的各种异常
     */
    @Override
    public Class<?> doCompile(String name, String source) throws Throwable {
        // 使用当前线程的上下文类加载器创建动态编译器实例
        DynamicCompiler dynamicCompiler = new DynamicCompiler(Thread.currentThread().getContextClassLoader());
        // 添加待编译的源文件
        dynamicCompiler.addSource(name, source);
        // 执行编译构建过程，返回编译生成的类映射表
        Map<String, Class<?>> build = dynamicCompiler.build();
        // 如果没有生成任何类，返回 null
        if (build.isEmpty()) {
            return null;
        }
        // 返回编译生成的第一个类（通常只有一个）
        return build.values().iterator().next();
    }

    /**
     * 动态类加载器
     * 用于加载由内存中字节码生成的类，继承自父类加载器以支持委托机制
     */
    public static final class DynamicClassLoader extends ClassLoader {
        // 存储已编译的字节码映射：类名 -> 字节码对象
        /** 字节码缓存集合 */
        private final Map<String, MemoryByteCode> byteCodes = new HashMap<String, MemoryByteCode>();

        public DynamicClassLoader(ClassLoader classLoader) {
            super(classLoader);
        }

        /**
         * 获取所有已编译的字节码数组
         * @return 类名到字节码数组的映射
         */
        public Map<String, byte[]> getByteCodes() {
            Map<String, byte[]> result = new HashMap<String, byte[]>(byteCodes.size());
            for (Map.Entry<String, MemoryByteCode> entry : byteCodes.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getByteCode());
            }
            return result;
        }

        /**
         * 获取所有已编译的 Class 对象
         * 遍历字节码映射，通过 defineClass 方法将字节码转换为 Class 对象
         * @return 类名到 Class 对象的映射
         * @throws ClassNotFoundException 如果类定义失败
         */
        public Map<String, Class<?>> getClasses() throws ClassNotFoundException {
            Map<String, Class<?>> classes = new HashMap<String, Class<?>>(1 << 4);
            for (MemoryByteCode byteCode : byteCodes.values()) {
                classes.put(byteCode.getClassName(), findClass(byteCode.getClassName()));
            }
            return classes;
        }

        /**
         * 注册编译后的源文件字节码
         * @param byteCode 包含类名和字节码的对象
         */
        public void registerCompiledSource(MemoryByteCode byteCode) {
            byteCodes.put(byteCode.getClassName(), byteCode);
        }

        /**
         * 重写 findClass 方法，优先从内存字节码中查找类
         * 如果内存中存在该类，直接定义；否则委托给父类加载器
         */
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            MemoryByteCode byteCode = byteCodes.get(name);
            if (byteCode == null) {
                return super.findClass(name);
            }
            // 使用父类的 defineClass 方法将字节码转换为 Class 对象
            return super.defineClass(name, byteCode.getByteCode(), 0, byteCode.getByteCode().length);
        }
    }

    /**
     * 动态 Java 文件管理器
     * 扩展 ForwardingJavaFileManager，拦截文件输出操作，将编译结果保存到内存中
     * 同时支持从 ClassLoader 中查找包内的类文件
     */
    static final class DynamicJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        // 需要转发给标准文件管理器的位置名称（平台类路径和系统模块）
        /** 父级类路径位置名称 */
        private static final String[] SUPER_LOCATION_NAMES = {StandardLocation.PLATFORM_CLASS_PATH.name(),
                /** JPMS StandardLocation.SYSTEM_MODULES **/
                "SYSTEM_MODULES"};
        
        // 用于在 ClassLoader 中查找包内部类的工具
        /** 包内部查找器 */
        private final PackageInternalsFinder finder;

        // 关联的动态类加载器
        /** 类加载器 */
        private final DynamicClassLoader classLoader;
        // 存储正在编译中的内存字节码列表
        /** 字节码缓存集合 */
        private final List<MemoryByteCode> byteCodes = new ArrayList<MemoryByteCode>();

        public DynamicJavaFileManager(JavaFileManager fileManager, DynamicClassLoader classLoader) {
            super(fileManager);
            this.classLoader = classLoader;
            // 初始化包内部类查找器
            finder = new PackageInternalsFinder(classLoader);
        }

        /**
         * 获取指定位置的类加载器
         * 始终返回当前的动态类加载器，确保新编译的类能被正确加载
         */
        @Override
        public ClassLoader getClassLoader(Location location) {
            return classLoader;
        }

        /**
         * 处理输出文件的创建
         * 当编译器需要写入 .class 文件时，重定向到内存中的 MemoryByteCode 对象
         */
        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) throws IOException {
            // 检查是否已经存在该类的编译对象
            for (MemoryByteCode byteCode : byteCodes) {
                if (byteCode.getClassName().equals(className)) {
                    return byteCode;
                }
            }
            // 创建新的内存字节码对象
            MemoryByteCode innerClass = new MemoryByteCode(className);
            byteCodes.add(innerClass);
            // 注册到类加载器以便后续加载
            classLoader.registerCompiledSource(innerClass);
            return innerClass;
        }

        /**
         * 推断二进制名称
         * 如果是自定义的文件对象，返回其二进制名称；否则交给标准管理器处理
         */
        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof CustomJavaFileObject) {
                return ((CustomJavaFileObject) file).binaryName();
            } else {
                /**
                 * 如果不是 CustomJavaFileObject，说明来自标准文件管理器
                 * - 让标准管理器处理文件名推断逻辑
                 */
                return super.inferBinaryName(location, file);
            }
        }

        /**
         * 列出指定位置的文件
         * 合并标准文件系统中的文件和 ClassLoader 中的包内类
         */
        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                                             boolean recurse) throws IOException {
            if (location instanceof StandardLocation) {
                String locationName = ((StandardLocation) location).name();
                // 对于平台和系统模块位置，直接委托给标准管理器
                for (String name : SUPER_LOCATION_NAMES) {
                    if (name.equals(locationName)) {
                        return super.list(location, packageName, kinds, recurse);
                    }
                }
            }

            // 合并 ClassPath 位置下的 Java 文件和 ClassLoader 中找到的类
            if (location == StandardLocation.CLASS_PATH && kinds.contains(JavaFileObject.Kind.CLASS)) {
                return new IterableJoin<JavaFileObject>(super.list(location, packageName, kinds, recurse),
                        finder.find(packageName));
            }

            return super.list(location, packageName, kinds, recurse);
        }

        // 辅助类：合并两个 Iterable
        static class IterableJoin<T> implements Iterable<T> {
            /** 第一个与第二个迭代器 */
            private final Iterable<T> first, next;

            public IterableJoin(Iterable<T> first, Iterable<T> next) {
                this.first = first;
                this.next = next;
            }

            @Override
            public Iterator<T> iterator() {
                return new IteratorJoin<T>(first.iterator(), next.iterator());
            }
        }

        // 辅助类：合并两个 Iterator
        static class IteratorJoin<T> implements Iterator<T> {
            /** 第一个与第二个迭代器 */
            private final Iterator<T> first, next;

            public IteratorJoin(Iterator<T> first, Iterator<T> next) {
                this.first = first;
                this.next = next;
            }

            @Override
            public boolean hasNext() {
                return first.hasNext() || next.hasNext();
            }

            @Override
            public T next() {
                if (first.hasNext()) {
                    return first.next();
                }
                return next.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }
        }
    }

    /**
     * 迭代器连接辅助类
     * 将两个迭代器串联起来，依次返回元素
     *
     * @param <T> 元素类型
     */
    static class IteratorJoin<T> implements Iterator<T> {
        /** 第一个与第二个迭代器 */
        private final Iterator<T> first, next;

        public IteratorJoin(Iterator<T> first, Iterator<T> next) {
            this.first = first;
            this.next = next;
        }

        @Override
        public boolean hasNext() {
            return first.hasNext() || next.hasNext();
        }

        @Override
        public T next() {
            if (first.hasNext()) {
                return first.next();
            }
            return next.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }
    }

    /**
     * 内存字节码文件对象
     * 继承 SimpleJavaFileObject，将编译后的 .class 字节码保存在 ByteArrayOutputStream 中
     * 避免写入磁盘，实现纯内存编译
     */
    static final class MemoryByteCode extends SimpleJavaFileObject {
        /** 包路径分隔符 */
        private static final char PKG_SEPARATOR = '.';
        /** 目录分隔符 */
        private static final char DIR_SEPARATOR = '/';
        /** 类文件后缀 */
        private static final String CLASS_FILE_SUFFIX = ".class";

        /** 字节数组输出流 */
        private ByteArrayOutputStream byteArrayOutputStream;

        public MemoryByteCode(String className) {
            super(URI.create("byte:///" + className.replace(PKG_SEPARATOR, DIR_SEPARATOR)
                    + Kind.CLASS.extension), Kind.CLASS);
        }

        public MemoryByteCode(String className, ByteArrayOutputStream byteArrayOutputStream)
                throws URISyntaxException {
            this(className);
            this.byteArrayOutputStream = byteArrayOutputStream;
        }

        /**
         * 获取编译后的字节码数组
         */
        public byte[] getByteCode() {
            return byteArrayOutputStream.toByteArray();
        }

        /**
         * 从 URI 中提取类名
         */
        public String getClassName() {
            String className = getName();
            className = className.replace(DIR_SEPARATOR, PKG_SEPARATOR);
            className = className.substring(1, className.indexOf(CLASS_FILE_SUFFIX));
            return className;
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            if (byteArrayOutputStream == null) {
                byteArrayOutputStream = new ByteArrayOutputStream();
            }
            return byteArrayOutputStream;
        }

    }

    /**
     * 包内部类查找器
     * 用于在 ClassLoader 的资源路径中查找特定包下的所有 .class 文件
     * 支持本地目录和 JAR 包两种场景
     */
    static final class PackageInternalsFinder {
        /** 类文件扩展名 */
        private static final String CLASS_FILE_EXTENSION = ".class";
        /** 类加载器 */
        private final ClassLoader classLoader;

        public PackageInternalsFinder(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        /**
         * 查找指定包名下的所有类文件对象
         * @param packageName 包名 (如: com.example)
         * @return 类文件对象列表
         * @throws IOException 读取资源时发生 IO 错误
         */
        public List<JavaFileObject> find(String packageName) throws IOException {
            String javaPackageName = packageName.replaceAll("\\.", "/");

            List<JavaFileObject> result = new ArrayList<JavaFileObject>();

            // 获取 ClassLoader 中该包对应的所有 URL 资源
            Enumeration<URL> urlEnumeration = classLoader.getResources(javaPackageName);
            // one URL for each jar on the classpath that has the given package
            while (urlEnumeration.hasMoreElements()) {
                URL element = urlEnumeration.nextElement();
                result.addAll(listUnder(packageName, element));
            }

            return result;
        }

        /**
         * 根据 URL 类型决定是扫描本地目录还是 JAR 包
         */
        private Collection<JavaFileObject> listUnder(String packageName, URL url) {
            File directory = new File(url.getFile());
            // 浏览本地 .class 文件 - 适用于本地执行环境
            if (directory.isDirectory()) {
                return processDir(packageName, directory);
            }
            // 浏览 JAR 文件
            return processJar(url);
        }


        /**
         * 处理 JAR 包中的类文件
         */
        private List<JavaFileObject> processJar(URL url) {
            List<JavaFileObject> result = new ArrayList<JavaFileObject>();
            try {
                // 提取 JAR 包的 URI 前缀
                String jarUri = url.toExternalForm().substring(0, url.toExternalForm().lastIndexOf(JAR_URL_SEPARATOR));

                JarURLConnection jarConn = (JarURLConnection) url.openConnection();
                String rootEntryName = jarConn.getEntryName();
                int rootEnd = rootEntryName.length() + 1;

                Enumeration<JarEntry> entryEnum = jarConn.getJarFile().entries();
                while (entryEnum.hasMoreElements()) {
                    JarEntry jarEntry = entryEnum.nextElement();
                    String name = jarEntry.getName();
                    // 筛选条件：在根目录下且以 .class 结尾
                    if (name.startsWith(rootEntryName) && name.indexOf('/', rootEnd) == -1 && name.endsWith(CLASS_FILE_EXTENSION)) {
                        URI uri = URI.create(jarUri + JAR_URL_SEPARATOR + name);
                        String binaryName = name.replaceAll("/", ".");
                        binaryName = binaryName.replaceAll(CLASS_FILE_EXTENSION + "$", "");

                        result.add(new CustomJavaFileObject(binaryName, uri));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Wasn't able to open " + url + " as a jar file", e);
            }
            return result;
        }

        /**
         * 处理本地目录中的类文件
         */
        private List<JavaFileObject> processDir(String packageName, File directory) {
            List<JavaFileObject> result = new ArrayList<JavaFileObject>();

            File[] childFiles = directory.listFiles();
            for (File childFile : childFiles) {
                if (childFile.isFile()) {
                    // 只处理 .class 文件
                    if (childFile.getName().endsWith(CLASS_FILE_EXTENSION)) {
                        String binaryName = packageName + "." + childFile.getName();
                        binaryName = binaryName.replaceAll(CLASS_FILE_EXTENSION + "$", "");

                        result.add(new CustomJavaFileObject(binaryName, childFile.toURI()));
                    }
                }
            }

            return result;
        }
    }

    /**
     * 动态编译器核心类
     * 封装了 JavaCompiler API 的调用流程，负责编译源码并返回 Class 对象或字节码
     */
    final class DynamicCompiler {
        /** Java 编译器 */
        private final JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        /** 标准文件管理器 */
        private final StandardJavaFileManager standardFileManager;
        /** 选项列表 */
        private final List<String> options = new ArrayList<>();
        /** 动态类加载器 */
        private final DynamicClassLoader dynamicClassLoader;

        /** 编译单元集合 */
        private final Collection<JavaFileObject> compilationUnits = new ArrayList<>();
        /** 编译错误列表 */
        private final List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        /** 编译警告列表 */
        private final List<Diagnostic<? extends JavaFileObject>> warnings = new ArrayList<>();

        /** 写入器 */
        private final Writer writer;

        public DynamicCompiler(ClassLoader classLoader) {
            this(classLoader, null);
        }

        public DynamicCompiler(ClassLoader classLoader, Writer writer) {
            standardFileManager = javaCompiler.getStandardFileManager(null, null, null);

            options.add("-Xlint:unchecked");
            dynamicClassLoader = new DynamicClassLoader(classLoader);
            this.writer = writer;
        }

        /**
         * 添加源码字符串进行编译
         */
        public void addSource(String className, String source) {
            addSource(new StringSource(className, source));
        }

        /**
         * 添加 JavaFileObject 进行编译
         */
        public void addSource(JavaFileObject javaFileObject) {
            compilationUnits.add(javaFileObject);
        }

        /**
         * 执行编译并返回编译后的 Class 对象映射
         * @return 类名到 Class 对象的映射
         */
        public Map<String, Class<?>> build() {

            errors.clear();
            warnings.clear();

            // 创建动态文件管理器
            JavaFileManager fileManager = new DynamicJavaFileManager(standardFileManager, dynamicClassLoader);

            DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<JavaFileObject>();
            // 创建编译任务
            JavaCompiler.CompilationTask task = javaCompiler.getTask(null, fileManager, collector, options, null,
                    compilationUnits);

            try {

                if (!compilationUnits.isEmpty()) {
                    boolean result = task.call();

                    // 检查编译结果和诊断信息
                    if (!result || collector.getDiagnostics().size() > 0) {

                        for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                            switch (diagnostic.getKind()) {
                                case NOTE:
                                case MANDATORY_WARNING:
                                case WARNING:
                                    warnings.add(diagnostic);
                                    break;
                                case OTHER:
                                case ERROR:
                                default:
                                    errors.add(diagnostic);
                                    break;
                            }

                        }

                        // 如果有错误，抛出异常
                        if (!errors.isEmpty()) {
                            throw new IllegalStateException("Compilation Error" + errors);
                        }
                    }
                }

                return dynamicClassLoader.getClasses();
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            } finally {
                compilationUnits.clear();

            }

        }

        /**
         * 执行编译并返回编译后的字节码映射
         * @return 类名到字节码数组的映射
         */
        public Map<String, byte[]> buildByteCodes() {

            errors.clear();
            warnings.clear();

            JavaFileManager fileManager = new DynamicJavaFileManager(standardFileManager, dynamicClassLoader);

            DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<JavaFileObject>();
            JavaCompiler.CompilationTask task = javaCompiler.getTask(null, fileManager, collector, options, null,
                    compilationUnits);

            try {

                if (!compilationUnits.isEmpty()) {
                    boolean result = task.call();

                    if (!result || collector.getDiagnostics().size() > 0) {

                        for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                            switch (diagnostic.getKind()) {
                                case NOTE:
                                case MANDATORY_WARNING:
                                case WARNING:
                                    warnings.add(diagnostic);
                                    break;
                                case OTHER:
                                case ERROR:
                                default:
                                    errors.add(diagnostic);
                                    break;
                            }

                        }

                        if (!errors.isEmpty()) {
                            throw new IllegalStateException("Compilation Error" + errors);
                        }
                    }
                }

                return dynamicClassLoader.getByteCodes();
            } catch (ClassFormatError e) {
                throw new IllegalStateException(e);
            } finally {
                compilationUnits.clear();

            }

        }

        /**
         * 获取动态类加载器
         */
        public ClassLoader getClassLoader() {
            return dynamicClassLoader;
        }

        /**
         * 获取编译错误信息列表
         */
        public List<String> getErrors() {
            return diagnosticToString(errors);
        }

        /**
         * 获取编译警告信息列表
         */
        public List<String> getWarnings() {
            return diagnosticToString(warnings);
        }

        /**
         * 将诊断信息转换为可读的字符串列表
         */
        private List<String> diagnosticToString(List<Diagnostic<? extends JavaFileObject>> diagnostics) {

            List<String> diagnosticMessages = new ArrayList<String>();

            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                diagnosticMessages.add(
                        "line: " + diagnostic.getLineNumber() + ", message: " + diagnostic.getMessage(Locale.US));
            }

            return diagnosticMessages;

        }
    }

    /**
     * 字符串源文件对象
     * 将 Java 源码字符串包装成 JavaFileObject，供编译器使用
     */
    public static class StringSource extends SimpleJavaFileObject {
        /** 文件内容 */
        private final String contents;

        public StringSource(String className, String contents) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.contents = contents;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return contents;
        }
    }

    /**
     * 自定义 Java 文件对象
     * 用于表示 JAR 包或本地文件系统中的类文件
     */
    public static class CustomJavaFileObject implements JavaFileObject {
        /** 二进制类名 */
        private final String binaryName;
        /** 文件 URI */
        private final URI uri;
        /**
         * 名称
         */
        private final String name;

        public CustomJavaFileObject(String binaryName, URI uri) {
            this.uri = uri;
            this.binaryName = binaryName;
            this.name = uri.getPath() == null ? uri.getSchemeSpecificPart() : uri.getPath();
        }

        @Override
        public URI toUri() {
            return this.uri;
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return this.uri.toURL().openStream();
        }

        @Override
        public OutputStream openOutputStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public Reader openReader(boolean ignoreEncodingErrors) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Writer openWriter() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLastModified() {
            return 0L;
        }

        @Override
        public boolean delete() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Kind getKind() {
            return Kind.CLASS;
        }

        @Override
        public boolean isNameCompatible(String simpleName, Kind kind) {
            String baseName = simpleName + kind.extension;
            return kind.equals(this.getKind()) && (baseName.equals(this.getName()) || this.getName().endsWith("/" + baseName));
        }

        @Override
        public NestingKind getNestingKind() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Modifier getAccessLevel() {
            throw new UnsupportedOperationException();
        }

        public String binaryName() {
            return this.binaryName;
        }

        @Override
        public String toString() {
            return this.getClass().getName() + CommonConstant.SYMBOL_LEFT_SQUARE_BRACKET + this.toUri() + CommonConstant.SYMBOL_RIGHT_SQUARE_BRACKET;
        }
    }
}