package com.chua.ast.support.processor;

import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * {@link AutoSpi} 注解处理器，编译期自动生成 {@code META-INF/extensions/} SPI 索引文件
 * <p>
 * 扫描标注了 {@code @AutoSpi} 的实现类，推导其对应的 SPI 接口与扩展别名，
 * 自动生成 {@code META-INF/extensions/<接口全限定名>} 配置文件，免去手动维护 SPI 索引。
 * </p>
 * <p>
 * 生成的文件格式与运行时 {@code CustomServiceResolver} 解析格式完全一致：
 * <ul>
 *     <li>{@code 实现类全限定名}</li>
 *     <li>{@code 别名=实现类全限定名}</li>
 * </ul>
 * </p>
 * <p>
 * 接口推导规则：优先使用 {@code @AutoSpi.value()} 显式指定；缺省时递归收集
 * 实现类及其父类实现的所有非 JDK 接口，每个接口各生成一份索引文件。
 * </p>
 * <p>
 * 别名推导规则：优先使用 {@code @AutoSpi.name()} 显式指定；其次读取实现类上的
 * common-starter {@code @Spi} / {@code @Extension} 注解（按全限定名反射匹配，避免模块依赖）；
 * 最后按「类名去掉接口名」推导（如 {@code MiniLMEmbeddingClient} 推导为 {@code MiniLM}）。
 * </p>
 * <p>
 * 与 {@code @Spi}/{@code @Extension} 共存规则：运行时 {@code ServiceDefinitionUtils} 优先读取
 * 类上的 {@code @Spi}/{@code @Extension} 注解生成名称，索引行别名仅在类无注解时生效。
 * 因此当实现类带这两个注解时，每个接口只生成一条「裸类名」发现行（不再为每个别名各写一行），
 * 避免运行时 N×M 重复注册，并自动清理历史构建遗留的冗余 {@code 别名=类名} 行；
 * 若此时仍显式指定 {@code @AutoSpi.name()}，将给出编译告警（该名称会被运行时忽略）。
 * </p>
 * <p>
 * 索引文件写入规则：若目标文件已存在（如仍手动维护的配置），读取已有内容，
 * 追加本次生成的新条目并自动去重（相同行只保留一份），不会覆盖已有配置。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.AutoSpi")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class AutoSpiAstProcessor extends AbstractProcessor {

    /**
     * SPI 索引文件目录
     */
    private static final String EXTENSIONS_PATH = "META-INF/extensions/";

    /**
     * common-starter {@code @Spi} 注解全限定名（按字符串匹配，避免 ast 模块依赖 common-starter）
     */
    private static final String SPI_ANNOTATION = "com.chua.common.support.spi.annotations.Spi";

    /**
     * common-starter {@code @Extension} 注解全限定名（按字符串匹配，避免 ast 模块依赖 common-starter）
     */
    private static final String EXTENSION_ANNOTATION = "com.chua.common.support.spi.annotations.Extension";

    /**
     * 索引内容：接口全限定名 -> 配置行集合（去重、有序）
     */
    private final Map<String, SortedSet<String>> index = new LinkedHashMap<>();

    /**
     * 注解派生别名的实现类：类全限定名 -> {@code @Spi}/{@code @Extension} 注解派生别名集合
     * <p>用于清理历史构建遗留的冗余 {@code 别名=类名} 行（运行时忽略这些别名，且会与新的发现行叠加导致重复注册）。</p>
     */
    private final Map<String, Set<String>> annotationDerivedAliases = new LinkedHashMap<>();

    /**
     * 编译期消息输出
     */
    private Messager messager;

    /**
     * 元素工具（用于获取接口的二进制名，保证与运行时 {@code Class.getTypeName()} 一致）
     */
    private javax.lang.model.util.Elements elementUtils;

    @Override
    /** 初始化 */
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.elementUtils = processingEnv.getElementUtils();
    }

    @Override
    /** 处理 */
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            generateIndexFiles();
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(AutoSpi.class)) {
            if (element instanceof TypeElement typeElement) {
                collect(typeElement);
            }
        }
        return false;
    }

    /**
     * 收集单个标注了 {@code @AutoSpi} 的实现类
     *
     * @param implElement 实现类元素
     */
    private void collect(TypeElement implElement) {
        ElementKind kind = implElement.getKind();
        if (kind != ElementKind.CLASS && kind != ElementKind.ENUM) {
            warn("@" + AutoSpi.class.getSimpleName() + " 只能标注在类或枚举上，已忽略: "
                    + implElement.getQualifiedName(), implElement);
            return;
        }
        if (implElement.getModifiers().contains(Modifier.ABSTRACT)) {
            warn("@" + AutoSpi.class.getSimpleName() + " 不能标注抽象类，已忽略: "
                    + implElement.getQualifiedName(), implElement);
            return;
        }

        AutoSpi annotation = implElement.getAnnotation(AutoSpi.class);
        if (annotation == null) {
            return;
        }

        String implFqn = implElement.getQualifiedName().toString();
        String implSimpleName = implElement.getSimpleName().toString();
        String[] explicitNames = annotation.name();

        // 类上是否同时存在 @Spi / @Extension：运行时注解名优先，索引行别名仅在无注解时生效
        boolean hasAnnotationNames = hasSpiOrExtension(implElement);
        if (hasAnnotationNames && explicitNames.length > 0) {
            warn("@" + AutoSpi.class.getSimpleName() + "(name = ...) 在类上同时存在 @Spi/@Extension 时"
                    + "会被运行时忽略（运行时注解名优先），已忽略 name: " + Arrays.toString(explicitNames),
                    implElement);
        }

        List<String> interfaces = readInterfaces(annotation, implElement);
        if (interfaces.isEmpty()) {
            warn("无法推导 " + implFqn + " 的 SPI 接口，请通过 @" + AutoSpi.class.getSimpleName()
                    + "(value = ...) 显式指定", implElement);
            return;
        }

        // 记录注解派生别名，供清理历史冗余行使用
        List<String> annotationAliases = hasAnnotationNames ? readAnnotationAliases(implElement)
                : Collections.emptyList();
        if (!annotationAliases.isEmpty()) {
            annotationDerivedAliases.computeIfAbsent(implFqn, k -> new LinkedHashSet<>())
                    .addAll(annotationAliases);
        }

        for (String iface : interfaces) {
            SortedSet<String> lines = index.computeIfAbsent(iface, k -> new TreeSet<>());
            if (hasAnnotationNames) {
                // 运行时注解名优先：每接口只写一条「裸类名」发现行，
                // 避免为每个注解别名各写一行导致运行时 N×M 重复注册
                lines.add(implFqn);
            } else if (explicitNames.length > 0) {
                for (String alias : explicitNames) {
                    lines.add(alias + "=" + implFqn);
                }
            } else {
                // 无别名：按「类名去掉接口名」推导单条配置
                String ifaceSimpleName = simpleName(iface);
                String derived = implSimpleName.replace(ifaceSimpleName, "");
                lines.add((derived.isEmpty() ? implSimpleName : derived) + "=" + implFqn);
            }
        }

        messager.printMessage(Diagnostic.Kind.NOTE,
                "已收集 SPI 实现: " + implFqn + " -> " + interfaces, implElement);
    }

    /**
     * 推导实现类对应的 SPI 接口列表
     * <p>
     * 优先使用注解 value 显式指定；缺省时递归收集实现类及其父类实现的所有非 JDK 接口。
     * </p>
     *
     * @param annotation  注解实例
     * @param implElement 实现类元素
     * @return 接口全限定名列表
     */
    private List<String> readInterfaces(AutoSpi annotation, TypeElement implElement) {
        List<String> result = new ArrayList<>();
        String[] explicit = annotation.value();
        if (explicit.length > 0) {
            for (String fqn : explicit) {
                // 若能解析为类型，统一转为二进制名（嵌套接口为 Outer$Inner），与运行时 type.getTypeName() 查找一致
                result.add(normalizeInterfaceName(fqn));
            }
            return result;
        }

        Set<String> collected = new LinkedHashSet<>();
        collectInterfaces(implElement, collected);
        result.addAll(collected);
        return result;
    }

    /**
     * 将显式指定的接口全限定名归一化为二进制名
     * <p>能通过 {@code Elements.getTypeElement} 解析时返回二进制名（嵌套接口为 {@code Outer$Inner}，
     * 与运行时 {@code Class.getTypeName()} 的索引查找一致）；无法解析时原样返回并告警。</p>
     *
     * @param fqn 接口全限定名（点分或 {@code $} 分隔均可）
     * @return 归一化后的接口名
     */
    private String normalizeInterfaceName(String fqn) {
        TypeElement resolved = elementUtils.getTypeElement(fqn);
        if (resolved != null && resolved.getKind() == ElementKind.INTERFACE) {
            return elementUtils.getBinaryName(resolved).toString();
        }
        return fqn;
    }

    /**
     * 递归收集实现类及其父类实现的所有非 JDK 接口
     *
     * @param type   当前类型
     * @param result 结果集合
     */
    private void collectInterfaces(TypeElement type, Set<String> result) {
        for (TypeMirror ifaceMirror : type.getInterfaces()) {
            if (ifaceMirror instanceof DeclaredType declared && declared.asElement() instanceof TypeElement iface
                    && iface.getKind() == ElementKind.INTERFACE) {
                // 使用二进制名（嵌套接口为 Outer$Inner），与运行时 type.getTypeName() 的索引查找一致
                String fqn = elementUtils.getBinaryName(iface).toString();
                if (!isJdkType(fqn)) {
                    result.add(fqn);
                }
            }
        }

        TypeMirror superclass = type.getSuperclass();
        if (superclass instanceof DeclaredType declared && declared.asElement() instanceof TypeElement parent) {
            collectInterfaces(parent, result);
        }
    }

    /**
     * 判断类上是否存在 common-starter {@code @Spi} / {@code @Extension} 注解
     * <p>运行时 {@code ServiceDefinitionUtils} 优先读取这两个注解生成名称，
     * 索引行别名仅在类无注解时生效，因此带注解的类只需生成「发现行」。</p>
     *
     * @param implElement 实现类元素
     * @return true 表示存在 {@code @Spi} / {@code @Extension}
     */
    private boolean hasSpiOrExtension(TypeElement implElement) {
        for (AnnotationMirror mirror : implElement.getAnnotationMirrors()) {
            String fqn = mirror.getAnnotationType().toString();
            if (SPI_ANNOTATION.equals(fqn) || EXTENSION_ANNOTATION.equals(fqn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取类上 {@code @Spi} / {@code @Extension} 注解的 value 值
     *
     * @param implElement 实现类元素
     * @return 注解声明的名称列表
     */
    private List<String> readAnnotationAliases(TypeElement implElement) {
        List<String> result = new ArrayList<>();
        for (AnnotationMirror mirror : implElement.getAnnotationMirrors()) {
            String fqn = mirror.getAnnotationType().toString();
            if (SPI_ANNOTATION.equals(fqn) || EXTENSION_ANNOTATION.equals(fqn)) {
                result.addAll(readAnnotationValue(mirror));
            }
        }
        return result;
    }

    /**
     * 读取注解镜像中名为 {@code value} 的属性值（支持 String 与 String[]）
     *
     * @param mirror 注解镜像
     * @return 属性值列表
     */
    private List<String> readAnnotationValue(AnnotationMirror mirror) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : mirror.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals("value")) {
                Object value = entry.getValue().getValue();
                if (value instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof AnnotationValue annotationValue && annotationValue.getValue() != null) {
                            result.add(String.valueOf(annotationValue.getValue()));
                        }
                    }
                } else if (value != null) {
                    result.add(String.valueOf(value));
                }
            }
        }
        return result;
    }

    /**
     * 从全限定名（或二进制名）中提取简单名
     * <p>同时按 {@code .} 与 {@code $} 切分，保证嵌套接口（二进制名 {@code Outer$Inner}）
     * 推导出的简单名与运行时 {@code Class.getSimpleName()} 一致。</p>
     *
     * @param fqn 全限定名或二进制名
     * @return 简单名
     */
    private String simpleName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        int lastDollar = fqn.lastIndexOf('$');
        int cut = Math.max(lastDot, lastDollar);
        return cut > 0 ? fqn.substring(cut + 1) : fqn;
    }

    /**
     * 判断全限定名是否为 JDK 内置类型
     *
     * @param fqn 全限定名
     * @return true 表示为 JDK 内置类型
     */
    private boolean isJdkType(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.")
                || fqn.startsWith("jdk.") || fqn.startsWith("sun.")
                || fqn.startsWith("com.sun.") || fqn.startsWith("org.w3c.")
                || fqn.startsWith("org.xml.") || fqn.startsWith("org.ietf.");
    }

    /**
     * 在最后一个处理轮次生成全部索引文件
     * <p>
     * 先读取已存在的索引文件内容（不存在则忽略），再追加本次生成的新条目并自动去重
     * （相同行只保留一份），保证不破坏手动维护的既有配置。
     * </p>
     */
    private void generateIndexFiles() {
        Filer filer = processingEnv.getFiler();
        for (Map.Entry<String, SortedSet<String>> entry : index.entrySet()) {
            String fileName = EXTENSIONS_PATH + entry.getKey();

            // 1. 读取已存在的索引文件内容（手动维护或历史生成），不存在则忽略
            Set<String> merged = new LinkedHashSet<>();
            boolean exists = readExisting(filer, fileName, merged);

            // 2. 清理历史冗余行：注解派生别名对应的「别名=类名」行
            //    （运行时忽略这些别名，且与新的发现行叠加会导致重复注册）
            pruneStaleAliasLines(merged);

            // 3. 追加本次生成的新条目（与已有内容自动去重）
            merged.addAll(entry.getValue());

            // 4. 写出合并后的完整内容
            writeIndexFile(filer, fileName, merged, exists);
        }
    }

    /**
     * 清理历史冗余索引行
     * <p>移除形如 {@code 别名=类全限定名} 且别名属于该类当前 {@code @Spi}/{@code @Extension}
     * 注解派生名的行——运行时读取类注解后这些行别名会被忽略，且与新的发现行叠加会造成重复注册。</p>
     *
     * @param merged 已合并的配置行集合（原地清理）
     */
    private void pruneStaleAliasLines(Set<String> merged) {
        if (annotationDerivedAliases.isEmpty()) {
            return;
        }
        merged.removeIf(line -> {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                return false;
            }
            String alias = line.substring(0, eq).trim();
            String implFqn = line.substring(eq + 1).trim();
            Set<String> derived = annotationDerivedAliases.get(implFqn);
            return derived != null && derived.contains(alias);
        });
    }

    /**
     * 读取已存在的索引文件内容到目标集合
     *
     * @param filer    Filer 实例
     * @param fileName 索引文件路径（{@code META-INF/extensions/...}）
     * @param merged   目标集合（保留原有行顺序）
     * @return true 表示文件已存在并读取成功；false 表示文件不存在
     */
    private boolean readExisting(Filer filer, String fileName, Set<String> merged) {
        try {
            FileObject existing = filer.getResource(StandardLocation.CLASS_OUTPUT, "", fileName);
            try (BufferedReader reader = new BufferedReader(existing.openReader(true))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        merged.add(trimmed);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            // 文件不存在，按新建处理
            return false;
        }
    }

    /**
     * 写出索引文件；若文件管理器拒绝覆盖已存在文件，则回退为直接以输出流写出合并内容
     *
     * @param filer    Filer 实例
     * @param fileName 索引文件路径（{@code META-INF/extensions/...}）
     * @param lines    合并后的配置行集合
     * @param exists   文件是否已存在（仅用于提示语）
     */
    private void writeIndexFile(Filer filer, String fileName, Set<String> lines, boolean exists) {
        try {
            FileObject fileObject = filer.createResource(StandardLocation.CLASS_OUTPUT, "", fileName);
            writeLines(fileObject, lines);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    (exists ? "已追加 SPI 索引条目: " : "已生成 SPI 索引文件: ") + fileName);
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (e instanceof FilerException || e instanceof java.nio.file.FileAlreadyExistsException
                    || message.contains("already exists")) {
                // 文件已存在且文件管理器拒绝覆盖：直接以输出流写出合并后的内容。
                // 注意：getResource() 返回的文件对象语义上仅用于读取，但 javac 的 RegularFileObject
                // 在 CLASS_OUTPUT 下允许 openOutputStream()，此处仅作为兜底路径使用。
                try {
                    FileObject existing = filer.getResource(StandardLocation.CLASS_OUTPUT, "", fileName);
                    writeLines(existing, lines);
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "已追加 SPI 索引条目: " + fileName);
                } catch (IOException e2) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "追加 SPI 索引文件失败: " + fileName + "，原因: "
                                    + (e2.getMessage() == null ? e2 : e2.getMessage()));
                }
            } else {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "生成 SPI 索引文件失败: " + fileName + "，原因: " + message);
            }
        }
    }

    /**
     * 将配置行集合以 UTF-8 写入文件对象（每行以换行符结尾）
     *
     * @param fileObject 文件对象
     * @param lines      配置行集合
     * @throws IOException 写入失败
     */
    private void writeLines(FileObject fileObject, Set<String> lines) throws IOException {
        try (OutputStream output = fileObject.openOutputStream()) {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            output.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 输出编译期警告
     *
     * @param message 警告信息
     * @param element 关联元素
     */
    private void warn(String message, Element element) {
        messager.printMessage(Diagnostic.Kind.WARNING, message, element);
    }
}
