package com.chua.common.support.objects.creator;

import com.chua.common.support.collection.SortedArrayList;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.compile.Compiler;
import com.chua.common.support.lang.compile.JdkCompiler;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.objects.DefaultObjectContext;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.ObjectContextConfig;
import com.chua.common.support.objects.definition.SingletonBeanDefinition;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.register.BeanDefinitionRegistry;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.TreeBasedTable;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quick 默认实现。
 *
 * <p>核心设计：</p>
 * <ul>
 *   <li><b>独立上下文</b> — 每个实例持有独立的 {@link DefaultObjectContext}，
 *       变量/常量/Bean 互不干扰，{@link #close()} 释放容器资源。</li>
 *   <li><b>动态类 SPI</b> — {@link #compile(String)} / {@link #dynamic(Class, String)}
 *       通过 {@link Compiler} SPI 解析编译器：common-starter 自带 {@link JdkCompiler} 兜底，
 *       引入 utils-support-asm-starter 后自动使用 ASM（含字节码 StackMapTable 后处理）。</li>
 *   <li><b>脚本执行</b> — 完整类源码直接编译运行；代码片段包装为 {@link QuickScript}
 *       实现类后编译执行，片段内可访问 {@code quick} 与 {@code variables}。</li>
 *   <li><b>XML/JSON 导入</b> — DOM 解析 XML、{@link Json} 解析 JSON，统一转为 Map 并绑定为变量。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see Quick
 * @see Compiler
 * @see QuickScript
 */
@Spi("quick")
@Slf4j
public class DefaultQuick implements Quick {

    /**
     * 动态脚本类名计数器，保证生成的类名唯一
     */
    private static final AtomicInteger SCRIPT_SEQ = new AtomicInteger(0);

    /**
     * 动态类默认包名
     */
    private static final String SCRIPT_PACKAGE = "com.chua.common.support.objects.creator.script";
    private static final String DYNAMIC_PACKAGE = "com.chua.common.support.objects.creator.dynamic";

    /**
     * 内部独立的 ObjectContext（轻量 IoC 容器）
     */
    private final ObjectContext context;

    /**
     * 导入的包路径集合
     */
    private final Set<String> importPackages = new LinkedHashSet<>();

    /**
     * 常量绑定（不可变）
     */
    private final Map<String, Object> constants = new LinkedHashMap<>();

    /**
     * 变量绑定（可变）
     */
    private final Map<String, Object> variables = new ConcurrentHashMap<>();

    /**
     * 最近一次导入的数据（fromXml / fromJson）
     */
    private Object data;

    /**
     * 类加载器
     */
    private final ClassLoader classLoader;

    /**
     * 创建 Quick 实例（内部独立 ObjectContext）。
     */
    public DefaultQuick() {
        this(ClassUtils.getDefaultClassLoader());
    }

    /**
     * 创建 Quick 实例，指定类加载器。
     *
     * @param classLoader 类加载器
     */
    public DefaultQuick(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader();
        this.context = createIsolatedContext();
    }

    /**
     * 创建 Quick 实例，复用外部 ObjectContext。
     *
     * <p>注意：外部传入的容器沿用其自身的注册中心（含 SPI 共享注册器），
     * 变量/常量仍为 Quick 实例私有，但 Bean 可能在共享注册器间可见，
     * 需由调用方保证隔离性。</p>
     *
     * @param context 外部对象容器，null 时创建隔离容器
     */
    public DefaultQuick(ObjectContext context) {
        this.classLoader = ClassUtils.getDefaultClassLoader();
        if (context != null) {
            this.context = context;
        } else {
            this.context = createIsolatedContext();
        }
    }

    /**
     * 创建隔离的 ObjectContext。
     *
     * <p>SPI 发现的 {@link BeanDefinitionRegister} 实现被 {@link ServiceProvider} 缓存为全局单例
     * （ServiceDefinition.getObj 缓存实例），若直接复用会导致不同 Quick 实例的 Bean
     * 相互可见。因此此处以 {@code spiEnabled=false} 初始化容器（不加载共享注册器），
     * 再按实现类逐一创建<b>全新实例</b>并注册，保证每个 Quick 拥有完全独立的注册中心。</p>
     *
     * @return 隔离的 ObjectContext
     */
    private static ObjectContext createIsolatedContext() {
        DefaultObjectContext context = new DefaultObjectContext();
        context.init(ObjectContextConfig.builder().spiEnabled(false).build());
        try {
            BeanDefinitionRegistry registry = context.getRegistry();
            ServiceProvider<BeanDefinitionRegister> provider = ServiceProvider.of(BeanDefinitionRegister.class);
            boolean addedAny = false;
            for (Map.Entry<String, Class<BeanDefinitionRegister>> entry : provider.listType().entrySet()) {
                Class<?> implClass = entry.getValue();
                try {
                    Object fresh = ClassUtils.forObject(implClass);
                    if (fresh instanceof BeanDefinitionRegister register && registry.addRegister(register)) {
                        addedAny = true;
                    }
                } catch (Exception e) {
                    // default 注册器是注册兜底（support() 回退目标），失败必须告警
                    if ("default".equalsIgnoreCase(entry.getKey())) {
                        log.warn("创建独立默认 BeanDefinitionRegister 失败: {}", implClass.getName(), e);
                    } else {
                        log.debug("创建独立 BeanDefinitionRegister 失败: {}", implClass.getName(), e);
                    }
                }
            }
            if (!addedAny) {
                log.warn("未添加任何独立 BeanDefinitionRegister，隔离注册中心可能无法注册 Bean");
            }
        } catch (Exception e) {
            log.warn("初始化隔离注册中心失败", e);
        }
        return context;
    }

    @Override
    /** 获取Context */
    public ObjectContext context() {
        return context;
    }

    // ==================== 脚本绑定 ====================

    @Override
    /** 导入包 */
    public Quick importPackage(String... packages) {
        if (packages != null) {
            for (String pkg : packages) {
                if (pkg != null && !pkg.isBlank()) {
                    importPackages.add(pkg.trim());
                }
            }
        }
        return this;
    }

    @Override
    /** 注册常量 */
    public Quick constant(String name, Object value) {
        if (name == null || name.isBlank()) {
            return this;
        }
        constants.put(name, value);
        registerNamedBean(name, value);
        return this;
    }

    @Override
    /** 注册变量 */
    public Quick variable(String name, Object value) {
        if (name == null || name.isBlank()) {
            return this;
        }
        variables.put(name, value);
        registerNamedBean(name, value);
        return this;
    }

    @Override
    /** 获取变量 */
    public Object variable(String name) {
        if (name == null) {
            return null;
        }
        return variables.get(name);
    }

    @Override
    /** 设置环境 */
    public Quick env(String key, Object value) {
        if (key != null && !key.isBlank()) {
            context.getEnvironment().setProperty(key, value);
        }
        return this;
    }

    // ==================== 数据导入 ====================

    @Override
    /** 从JSON导入 */
    public Quick fromJson(String json) {
        if (json == null || json.isBlank()) {
            return this;
        }
        Map<String, Object> map = Json.fromJson(json);
        this.data = map;
        bindData(map);
        return this;
    }

    @Override
    /** 从XML导入 */
    public Quick fromXml(String xml) {
        if (xml == null || xml.isBlank()) {
            return this;
        }
        Map<String, Object> map = xmlToMap(xml);
        this.data = map;
        bindData(map);
        return this;
    }

    @Override
    /** 从JSON导入并转换 */
    public <T> T fromJson(String json, Class<T> type) {
        if (type == null) {
            return null;
        }
        if (json != null && !json.isBlank()) {
            T bean = Json.fromJson(json, type);
            this.data = bean;
            registerNamedBean(type.getSimpleName(), bean);
            return bean;
        }
        return null;
    }

    @Override
    /** 从XML导入并转换 */
    public <T> T fromXml(String xml, Class<T> type) {
        if (type == null) {
            return null;
        }
        Map<String, Object> map = xmlToMap(xml);
        this.data = map;
        T bean = mapToBean(map, type);
        registerNamedBean(type.getSimpleName(), bean);
        return bean;
    }

    // ==================== 类初始化 ====================

    @Override
    /** 初始化类 */
    public <T> T init(Class<T> type) {
        if (type == null) {
            return null;
        }
        Object current = this.data;
        if (current instanceof Map<?, ?> map && !type.isInstance(current)) {
            T bean = mapToBean((Map<String, Object>) map, type);
            registerNamedBean(type.getSimpleName(), bean);
            return bean;
        }
        if (current != null && type.isInstance(current)) {
            T bean = type.cast(current);
            registerNamedBean(type.getSimpleName(), bean);
            return bean;
        }
        T bean = context.getBeanOfType(type);
        if (bean != null) {
            return bean;
        }
        try {
            context.registerBean(type);
        } catch (Exception e) {
            log.debug("从上下文注册 Bean 失败，直接创建实例: {}", type.getName(), e);
        }
        bean = context.getBeanOfType(type);
        if (bean != null) {
            return bean;
        }
        // 普通类（无 BeanDefinitionGenerator 支持）直接反射创建
        return ClassUtils.forObject(type);
    }

    @Override
    /** 初始化类（按名称） */
    public <T> T init(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        Class<?> type = resolveClass(className);
        if (type == null) {
            return null;
        }
        return (T) init(type);
    }

    // ==================== 集合构造器 ====================

    @Override
    /** 创建Map构造器 */
    public <K, V> MapBuilder<K, V> map() {
        return new DefaultMapBuilder<>();
    }

    @Override
    /** 创建List构造器 */
    public <E> ListBuilder<E> list() {
        return new DefaultListBuilder<>();
    }

    @Override
    /** 创建Table构造器 */
    public <R extends Comparable<? super R>, C extends Comparable<? super C>, V> TableBuilder<R, C, V> table() {
        return new DefaultTableBuilder<>();
    }

    // ==================== 动态类 ====================

    @Override
    /** 编译源码 */
    public Class<?> compile(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        Compiler compiler = resolveCompiler();
        return compiler.compiler(source, classLoader);
    }

    @Override
    /** 生成动态子类 */
    public <T> T dynamic(Class<T> superType, String source) {
        if (superType == null || source == null) {
            return null;
        }
        String className = DYNAMIC_PACKAGE + ".Dynamic" + SCRIPT_SEQ.incrementAndGet();
        String keyword = superType.isInterface() ? "implements" : "extends";
        // 使用 canonical name（嵌套类为 Outer.Inner），二进制名（Outer$Inner）无法被 javac 源码引用
        String superTypeName = superType.getCanonicalName() != null ? superType.getCanonicalName() : superType.getName();
        String fullSource = "package " + DYNAMIC_PACKAGE + ";\n"
                + buildImports()
                + "public class " + className.substring(className.lastIndexOf('.') + 1)
                + " " + keyword + " " + superTypeName + " {\n"
                + source + "\n}\n";
        Class<?> clazz = compile(fullSource);
        if (clazz == null) {
            return null;
        }
        return (T) ClassUtils.forObject(clazz);
    }

    // ==================== 脚本执行 ====================

    @Override
    /** 执行脚本 */
    public Object execute(String script) {
        if (script == null || script.isBlank()) {
            return null;
        }
        String trimmed = script.trim();
        if (looksLikeFullClass(trimmed)) {
            return executeFullClass(trimmed);
        }
        return executeSnippet(trimmed);
    }

    @Override
    /** 执行脚本并转换类型 */
    public <T> T execute(String script, Class<T> returnType) {
        Object result = execute(script);
        if (returnType == null || result == null) {
            return (T) result;
        }
        return Converter.convertIfNecessary(result, returnType);
    }

    // ==================== Bean 访问 ====================

    @Override
    /** 注册Bean */
    public Quick register(String name, Object bean) {
        registerNamedBean(name, bean);
        return this;
    }

    @Override
    /** 获取Bean */
    public <T> T get(String name) {
        if (name == null) {
            return null;
        }
        Object value = variables.get(name);
        if (value == null) {
            value = constants.get(name);
        }
        if (value != null) {
            return (T) value;
        }
        if (context.containsBean(name)) {
            return (T) context.getRegistry().getBean(name, Object.class);
        }
        return null;
    }

    @Override
    /** 获取BeanOfType */
    public <T> T get(Class<T> type) {
        if (type == null) {
            return null;
        }
        return context.getBeanOfType(type);
    }

    @Override
    /** 关闭Quick */
    public void close() {
        try {
            context.close();
        } catch (Exception e) {
            log.warn("关闭 ObjectContext 失败", e);
        }
    }

    // ==================== 内部辅助 ====================

    /**
     * 注册具名 Bean 到内部上下文。
     *
     * @param name Bean 名称
     * @param bean Bean 实例
     */
    private void registerNamedBean(String name, Object bean) {
        if (name == null || bean == null || context.isClosed()) {
            return;
        }
        try {
            SingletonBeanDefinition definition = SingletonBeanDefinition.of(bean);
            definition.setName(name);
            context.registerBean(definition);
        } catch (Exception e) {
            log.debug("注册 Bean 失败: name={}", name, e);
        }
    }

    /**
     * 将导入的 Map 数据绑定为变量。
     *
     * @param map 数据 Map
     */
    private void bindData(Map<String, Object> map) {
        if (map == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            variables.put(entry.getKey(), entry.getValue());
            registerNamedBean(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 构建脚本公共 import 语句（Quick 包 + JDK 集合 + 用户导入包）。
     *
     * @return import 语句字符串
     */
    private String buildScriptImports() {
        return "import " + Quick.class.getPackageName() + ".*;\n"
                + "import java.util.*;\n"
                + "import java.util.stream.*;\n"
                + buildImports();
    }

    /**
     * 构建导入包对应的 import 语句。
     *
     * @return import 语句字符串
     */
    private String buildImports() {
        StringBuilder sb = new StringBuilder();
        for (String pkg : importPackages) {
            sb.append("import ").append(pkg).append(".*;\n");
        }
        return sb.toString();
    }

    /**
     * 判断脚本是否为完整类源码。
     *
     * @param trimmed 去除首尾空白的脚本
     * @return true 表示完整类源码
     */
    private boolean looksLikeFullClass(String trimmed) {
        return trimmed.startsWith("package ")
                || trimmed.contains(" class ")
                || trimmed.contains(" interface ")
                || trimmed.startsWith("class ")
                || trimmed.startsWith("interface ")
                || trimmed.startsWith("record ")
                || trimmed.contains(" record ");
    }

    /**
     * 执行完整类源码脚本。
     *
     * <p>编译实例化后：</p>
     * <ul>
     *   <li>实现 {@link QuickScript} → 调用 {@link QuickScript#run(Quick, Map)}</li>
     *   <li>存在静态 {@code main(String[])} → 调用 main</li>
     *   <li>存在无参 {@code run()}/{@code execute()} → 调用</li>
     * </ul>
     *
     * @param source 完整类源码
     * @return 执行结果
     */
    private Object executeFullClass(String source) {
        String processed = source;
        // 无 package 声明的完整类源码：自动注入公共 import（Quick 包 + JDK 集合 + 用户导入包）
        if (!source.trim().startsWith("package ")) {
            processed = buildScriptImports() + "\n" + source;
        }
        Class<?> clazz = compile(processed);
        if (clazz == null) {
            return null;
        }
        try {
            Object instance = ClassUtils.forObject(clazz);
            if (instance instanceof QuickScript quickScript) {
                return quickScript.run(this, bindings());
            }
            for (String methodName : new String[]{"run", "execute"}) {
                try {
                    Method method = clazz.getMethod(methodName);
                    if (method.getParameterCount() == 0) {
                        return method.invoke(instance);
                    }
                } catch (NoSuchMethodException ignored) {
                    // 继续尝试下一个方法
                }
            }
            try {
                Method main = clazz.getMethod("main", String[].class);
                if (Modifier.isStatic(main.getModifiers())) {
                    main.invoke(null, (Object) new String[0]);
                }
            } catch (NoSuchMethodException ignored) {
                // 无 main 方法
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("脚本执行失败", e);
        }
    }

    /**
     * 执行代码片段（包装为 QuickScript 实现类）。
     *
     * <p>片段若以 {@code ;} 结尾视为语句体直接嵌入；否则视为表达式，包装为 {@code return (expr);}。</p>
     *
     * @param snippet 代码片段
     * @return 执行结果
     */
    private Object executeSnippet(String snippet) {
        String body;
        if (snippet.endsWith(";") || snippet.contains(";")) {
            body = snippet;
        } else {
            body = "return (" + snippet + ");";
        }
        String className = "QuickScript" + SCRIPT_SEQ.incrementAndGet();
        String source = "package " + SCRIPT_PACKAGE + ";\n"
                + buildScriptImports()
                + "public class " + className + " implements " + QuickScript.class.getName() + " {\n"
                + "    public Object run(" + Quick.class.getName() + " quick, Map<String, Object> variables) {\n"
                + "        " + body + "\n"
                + "    }\n"
                + "}\n";
        Class<?> clazz = compile(source);
        if (clazz == null) {
            return null;
        }
        try {
            Object instance = ClassUtils.forObject(clazz);
            if (instance instanceof QuickScript quickScript) {
                return quickScript.run(this, bindings());
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("脚本片段执行失败", e);
        }
    }

    /**
     * 构建绑定变量快照（常量 + 变量）。
     *
     * @return 绑定变量 Map
     */
    private Map<String, Object> bindings() {
        Map<String, Object> result = new LinkedHashMap<>(constants);
        result.putAll(variables);
        return result;
    }

    /**
     * 解析编译器（Compiler SPI）。
     *
     * <p>优先获取 {@code "asm"} 实现（ASM 字节码后处理），其次 {@code "groovy"}，
     * 最后回退到 common-starter 自带的 {@link JdkCompiler}。</p>
     *
     * @return Compiler 实例
     */
    private Compiler resolveCompiler() {
        try {
            ServiceProvider<Compiler> provider = ServiceProvider.of(Compiler.class);
            Compiler compiler = provider.getExtension("asm");
            if (compiler != null) {
                return compiler;
            }
            compiler = provider.getExtension("groovy");
            if (compiler != null) {
                return compiler;
            }
            List<Compiler> all = provider.collect();
            if (all != null && !all.isEmpty()) {
                return all.get(0);
            }
        } catch (Exception e) {
            log.debug("Compiler SPI 解析失败，回退 JdkCompiler", e);
        }
        return new JdkCompiler();
    }

    /**
     * 按名称解析类（含导入包前缀）。
     *
     * @param className 类名（全限定名或简单名）
     * @return Class，解析失败返回 null
     */
    private Class<?> resolveClass(String className) {
        Class<?> type = ClassUtils.forName(className, classLoader);
        if (type != null) {
            return type;
        }
        if (!className.contains(".")) {
            for (String pkg : importPackages) {
                type = ClassUtils.forName(pkg + "." + className, classLoader);
                if (type != null) {
                    return type;
                }
            }
        }
        return null;
    }

    /**
     * 将 Map 数据转换为目标类型对象（按字段名填充，自动类型转换）。
     *
     * @param map  数据 Map
     * @param type 目标类型
     * @param <T>  泛型类型
     * @return 转换后的对象，字段值无法转换时跳过该字段
     */
    private <T> T mapToBean(Map<String, Object> map, Class<T> type) {
        if (map == null || type == null) {
            return null;
        }
        T bean = ClassUtils.forObject(type);
        if (bean == null) {
            return null;
        }
        for (Field field : ClassUtils.getFields(type)) {
            if (!map.containsKey(field.getName())) {
                continue;
            }
            Object value = map.get(field.getName());
            if (value == null) {
                continue;
            }
            Class<?> fieldType = field.getType();
            Object converted = fieldType.isAssignableFrom(value.getClass())
                    ? value
                    : Converter.convertIfNecessary(value, fieldType);
            if (converted != null) {
                ClassUtils.setFieldValue(field, type, converted, bean);
            }
        }
        return bean;
    }

    /**
     * 将 XML 字符串解析为 Map。
     *
     * <p>基于 JDK DOM 解析，元素属性与子元素合并为 Map：子元素出现多次转为 List，
     * 纯文本元素直接取文本值。默认禁用外部实体（XXE 防护）。</p>
     *
     * @param xml XML 字符串
     * @return Map 结构，解析失败返回空 Map
     */
    private Map<String, Object> xmlToMap(String xml) {
        if (xml == null || xml.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = document.getDocumentElement();
            return elementToMap(root);
        } catch (Exception e) {
            log.warn("XML 解析失败", e);
            return Collections.emptyMap();
        }
    }

    /**
     * 将 DOM 元素解析为 Map。
     *
     * @param element DOM 元素
     * @return Map 结构
     */
    private Map<String, Object> elementToMap(Element element) {
        Map<String, Object> result = new LinkedHashMap<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            result.put(attr.getNodeName(), attr.getNodeValue());
        }
        NodeList children = element.getChildNodes();
        Map<String, List<Object>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element childElement = (Element) child;
                Object value;
                if (hasOnlyTextContent(childElement)) {
                    value = childElement.getTextContent().trim();
                } else {
                    value = elementToMap(childElement);
                }
                grouped.computeIfAbsent(childElement.getTagName(), k -> new ArrayList<>()).add(value);
            }
        }
        for (Map.Entry<String, List<Object>> entry : grouped.entrySet()) {
            if (entry.getValue().size() == 1) {
                result.put(entry.getKey(), entry.getValue().get(0));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 判断元素是否只包含文本内容。
     *
     * @param element DOM 元素
     * @return true 表示无子元素
     */
    private boolean hasOnlyTextContent(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return false;
            }
        }
        return true;
    }

    // ==================== 集合构造器实现 ====================

    /**
     * Map 构造器默认实现。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    static class DefaultMapBuilder<K, V> implements MapBuilder<K, V> {

        /**
         * 中间存储（保持插入顺序）
         */
        private final Map<K, V> values = new LinkedHashMap<>();

        /**
         * 目标实现类型
         */
        private String type = "hash";

        @Override
        /** 添加键值对 */
        public MapBuilder<K, V> put(K key, V value) {
            values.put(key, value);
            return this;
        }

        @Override
        /** 指定实现类型 */
        public MapBuilder<K, V> type(String type) {
            if (type != null && !type.isBlank()) {
                this.type = type.trim().toLowerCase();
            }
            return this;
        }

        @Override
        /** 构建Map */
        public Map<K, V> build() {
            return switch (type) {
                case "linked" -> new LinkedHashMap<>(values);
                case "tree" -> new TreeMap<>(values);
                case "concurrent" -> new ConcurrentHashMap<>(values);
                default -> new LinkedHashMap<>(values);
            };
        }
    }

    /**
     * List 构造器默认实现。
     *
     * @param <E> 元素类型
     */
    static class DefaultListBuilder<E> implements ListBuilder<E> {

        /**
         * 中间存储
         */
        private final List<E> values = new ArrayList<>();

        /**
         * 目标实现类型
         */
        private String type = "array";

        @Override
        /** 添加元素 */
        public ListBuilder<E> add(E value) {
            values.add(value);
            return this;
        }

        @Override
        /** 指定实现类型 */
        public ListBuilder<E> type(String type) {
            if (type != null && !type.isBlank()) {
                this.type = type.trim().toLowerCase();
            }
            return this;
        }

        @Override
        /** 构建List */
        public List<E> build() {
            return switch (type) {
                case "linked" -> new LinkedList<>(values);
                case "sorted" -> {
                    SortedArrayList<E> sorted = new SortedArrayList<>((Comparator<? super E>) Comparator.naturalOrder());
                    sorted.addAll(values);
                    yield sorted;
                }
                case "sync" -> new CopyOnWriteArrayList<>(values);
                default -> new ArrayList<>(values);
            };
        }
    }

    /**
     * Table 构造器默认实现。
     *
     * @param <R> 行类型（Comparable）
     * @param <C> 列类型（Comparable）
     * @param <V> 值类型
     */
    static class DefaultTableBuilder<R extends Comparable<? super R>, C extends Comparable<? super C>, V> implements TableBuilder<R, C, V> {

        /**
         * 目标实现类型
         */
        private String type = "hash";

        /**
         * HashBasedTable 中间存储
         */
        private final Table<R, C, V> hashValues = HashBasedTable.create();

        /**
         * TreeBasedTable 中间存储
         */
        private final Table<R, C, V> treeValues = TreeBasedTable.create();

        @Override
        /** 添加单元格 */
        public TableBuilder<R, C, V> put(R rowKey, C columnKey, V value) {
            if ("tree".equals(type)) {
                treeValues.put(rowKey, columnKey, value);
            } else {
                hashValues.put(rowKey, columnKey, value);
            }
            return this;
        }

        @Override
        /** 指定实现类型 */
        public TableBuilder<R, C, V> type(String type) {
            if (type != null && !type.isBlank()) {
                this.type = type.trim().toLowerCase();
            }
            return this;
        }

        @Override
        /** 构建Table */
        public Table<R, C, V> build() {
            return "tree".equals(type) ? treeValues : hashValues;
        }
    }
}