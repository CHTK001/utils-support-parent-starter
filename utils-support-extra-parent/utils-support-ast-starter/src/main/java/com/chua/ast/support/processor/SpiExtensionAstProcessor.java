package com.chua.ast.support.processor;

import com.chua.ast.support.annotation.SpiExtension;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * {@link SpiExtension} 注解处理器，编译期自动生成 {@code META-INF/extensions/} SPI 索引文件
 * <p>
 * 扫描标注了 {@code @SpiExtension} 的实现类，推导其对应的 SPI 接口与扩展别名，
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
 * 接口推导规则：优先使用 {@code @SpiExtension.value()} 显式指定；缺省时递归收集
 * 实现类及其父类实现的所有非 JDK 接口，每个接口各生成一份索引文件。
 * </p>
 * <p>
 * 别名推导规则：优先使用 {@code @SpiExtension.name()} 显式指定；其次读取实现类上的
 * common-starter {@code @Spi} / {@code @Extension} 注解（按全限定名反射匹配，避免模块依赖）；
 * 最后按「类名去掉接口名」推导（如 {@code MiniLMEmbeddingClient} 推导为 {@code MiniLM}）。
 * </p>
 * <p>
 * 若目标索引文件已存在（如仍手动维护的配置文件），跳过生成并输出警告，避免破坏既有配置。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.SpiExtension")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class SpiExtensionAstProcessor extends AbstractProcessor {

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
     * 编译期消息输出
     */
    private Messager messager;

    /**
     * 元素工具（用于获取接口的二进制名，保证与运行时 {@code Class.getTypeName()} 一致）
     */
    private javax.lang.model.util.Elements elementUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.elementUtils = processingEnv.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            generateIndexFiles();
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(SpiExtension.class)) {
            if (element instanceof TypeElement typeElement) {
                collect(typeElement);
            }
        }
        return false;
    }

    /**
     * 收集单个标注了 {@code @SpiExtension} 的实现类
     *
     * @param implElement 实现类元素
     */
    private void collect(TypeElement implElement) {
        ElementKind kind = implElement.getKind();
        if (kind != ElementKind.CLASS && kind != ElementKind.ENUM) {
            warn("@" + SpiExtension.class.getSimpleName() + " 只能标注在类或枚举上，已忽略: "
                    + implElement.getQualifiedName(), implElement);
            return;
        }
        if (implElement.getModifiers().contains(Modifier.ABSTRACT)) {
            warn("@" + SpiExtension.class.getSimpleName() + " 不能标注抽象类，已忽略: "
                    + implElement.getQualifiedName(), implElement);
            return;
        }

        SpiExtension annotation = implElement.getAnnotation(SpiExtension.class);
        if (annotation == null) {
            return;
        }

        String implFqn = implElement.getQualifiedName().toString();
        String implSimpleName = implElement.getSimpleName().toString();
        List<String> aliases = readAliases(annotation, implElement);

        List<String> interfaces = readInterfaces(annotation, implElement);
        if (interfaces.isEmpty()) {
            warn("无法推导 " + implFqn + " 的 SPI 接口，请通过 @" + SpiExtension.class.getSimpleName()
                    + "(value = ...) 显式指定", implElement);
            return;
        }

        for (String iface : interfaces) {
            SortedSet<String> lines = index.computeIfAbsent(iface, k -> new TreeSet<>());
            if (aliases.isEmpty()) {
                // 无别名：按「类名去掉接口名」推导单条配置
                String ifaceSimpleName = simpleName(iface);
                String derived = implSimpleName.replace(ifaceSimpleName, "");
                lines.add((derived.isEmpty() ? implSimpleName : derived) + "=" + implFqn);
            } else {
                for (String alias : aliases) {
                    lines.add(alias + "=" + implFqn);
                }
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
     * @param annotation   注解实例
     * @param implElement  实现类元素
     * @return 接口全限定名列表
     */
    private List<String> readInterfaces(SpiExtension annotation, TypeElement implElement) {
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
     * 推导实现类的扩展别名
     * <p>
     * 优先注解 name；其次读取实现类上的 {@code @Spi} / {@code @Extension} 注解（字符串反射匹配）；
     * 均为空时返回空列表，由调用方按类名推导。
     * </p>
     *
     * @param annotation  注解实例
     * @param implElement 实现类元素
     * @return 别名列表（可为空）
     */
    private List<String> readAliases(SpiExtension annotation, TypeElement implElement) {
        String[] explicit = annotation.name();
        if (explicit.length > 0) {
            return Arrays.asList(explicit);
        }

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
     */
    private void generateIndexFiles() {
        for (Map.Entry<String, SortedSet<String>> entry : index.entrySet()) {
            String iface = entry.getKey();
            String fileName = EXTENSIONS_PATH + iface;
            try {
                FileObject fileObject = processingEnv.getFiler()
                        .createResource(StandardLocation.CLASS_OUTPUT, "", fileName);
                try (OutputStream output = fileObject.openOutputStream()) {
                    StringBuilder sb = new StringBuilder();
                    for (String line : entry.getValue()) {
                        sb.append(line).append('\n');
                    }
                    output.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "已生成 SPI 索引文件: " + fileName);
            } catch (IOException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (message.contains("already exists") || e instanceof FilerException
                        || e instanceof java.nio.file.FileAlreadyExistsException) {
                    messager.printMessage(Diagnostic.Kind.WARNING,
                            "SPI 索引文件已存在，跳过自动生成（如需自动生成请删除手动配置）: " + fileName);
                } else {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "生成 SPI 索引文件失败: " + fileName + "，原因: " + message);
                }
            }
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
