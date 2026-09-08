package com.chua.common.support.objects.creator;

import com.chua.common.support.objects.ObjectContext;
import com.google.common.collect.Table;

import java.util.List;
import java.util.Map;

/**
 * Quick 快速创建门面接口。
 *
 * <p>提供「脚本式 DSL 门面」能力，一条链式调用即可完成：包导入、常量/变量/环境绑定、
 * 类初始化、XML/JSON 数据导入、Map/List/Table 集合构造、动态类生成与脚本执行。</p>
 *
 * <p>每个 {@link Quick} 实例内部持有<b>独立的 {@link ObjectContext}</b>（轻量 IoC 容器），
 * 互不干扰，关闭后自动释放资源。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 基本链式创建
 * Quick quick = Quick.create()
 *     .importPackage("java.time", "java.util")
 *     .constant("PI", 3.14)
 *     .variable("name", "zhang")
 *     .env("server.port", "8080");
 *
 * // 集合构造器（与动态类不同的链式构造器）
 * Map<String, Object> map = quick.map().put("a", 1).put("b", 2).build();
 * List<Object> list = quick.list().add("x").add("y").build();
 * Table<String, String, Object> table = quick.table()
 *     .put("r1", "c1", 1)
 *     .put("r1", "c2", 2)
 *     .build();
 *
 * // 脚本执行（Java 源码，Compiler SPI 自动发现 asm/groovy 等实现）
 * Object result = quick.execute("1 + 2");
 *
 * // 动态类生成（Compiler SPI：asm/javassist/jdk）
 * Runnable task = quick.dynamic(Runnable.class, "public void run() { System.out.println(\"hi\"); }");
 *
 * // XML/JSON 导入数据
 * quick.fromJson("{\"name\":\"zhang\",\"age\":25}");
 * quick.fromXml("<user><name>li</name><age>30</age></user>");
 * }</pre>
 *
 * <h2>完整链式调用示例（importPackage + fromXml + dynamic + execute 组合）</h2>
 * <pre>{@code
 * // 1. 链式初始化：包导入 + 常量/变量/环境绑定 + XML 数据导入（这些方法均返回 Quick）
 * Quick quick = Quick.create()
 *     .importPackage("java.time", "java.util")
 *     .constant("PI", 3.14)
 *     .variable("factor", 2)
 *     .env("server.port", "8080")
 *     .fromXml("<config><name>zhang</name><age>25</age></config>");
 *
 * // 2. 导入的数据可直接用于类初始化（init 优先消费 fromXml/fromJson 导入的数据）
 * User user = quick.init(User.class);      // 假设存在 User 业务实体类：name=zhang, age=25
 *
 * // 3. 动态类生成：为接口/类生成子类并实例化，源码自动带导入包（java.time.*）
 * Runnable task = quick.dynamic(Runnable.class,
 *         "public void run() { System.out.println(\"today=\" + LocalDate.now()); }");
 * task.run();                              // today=<运行当天日期>
 *
 * // 4. 脚本执行：代码片段内可访问 quick（当前实例）与 variables（绑定变量快照）
 * Object sum = quick.execute(
 *         "return ((Number) variables.get(\"factor\")).intValue() + 1;");   // 3
 *
 * // 5. 脚本执行：类型化返回
 * Double area = quick.execute("2 * Math.PI", Double.class);                  // 6.283...
 *
 * // 6. 脚本执行：完整类源码（实现 QuickScript，run 接收 quick 与 variables）
 * Object name = quick.execute(
 *         "public class MyScript implements com.chua.common.support.objects.creator.QuickScript {"
 *       + "  public Object run(Quick quick, Map<String, Object> variables) {"
 *       + "    return variables.get(\"name\");"                             // zhang
 *       + "  }"
 *       + "}");
 *
 * // 7. 用完释放内部上下文
 * quick.close();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see DefaultQuick
 * @see ObjectContext
 */
public interface Quick extends AutoCloseable {

    // ==================== 创建 ====================

    /**
     * 创建 Quick 实例（内部独立 ObjectContext，默认配置）。
     *
     * @return Quick 实例
     */
    static Quick create() {
        return new DefaultQuick();
    }

    /**
     * 创建 Quick 实例，复用指定类加载器。
     *
     * @param classLoader 类加载器，null 时使用线程上下文类加载器
     * @return Quick 实例
     */
    static Quick create(ClassLoader classLoader) {
        return new DefaultQuick(classLoader);
    }

    /**
     * 创建 Quick 实例，复用外部 ObjectContext。
     *
     * @param context 外部对象容器，null 时创建独立容器
     * @return Quick 实例
     */
    static Quick create(ObjectContext context) {
        return new DefaultQuick(context);
    }

    // ==================== 上下文 ====================

    /**
     * 获取内部独立的 ObjectContext。
     *
     * <p>每个 Quick 实例持有独立的轻量 IoC 容器，用于变量/常量/Bean 的注册与查找。</p>
     *
     * @return ObjectContext 实例
     */
    ObjectContext context();

    // ==================== 脚本绑定 ====================

    /**
     * 导入包路径，供脚本源码生成时自动添加 import 语句。
     *
     * @param packages 包名数组，如 {@code "java.time"}、{@code "java.util"}
     * @return 当前 Quick 实例（链式）
     */
    Quick importPackage(String... packages);

    /**
     * 注册常量（不可变绑定）。
     *
     * @param name  常量名
     * @param value 常量值
     * @return 当前 Quick 实例（链式）
     */
    Quick constant(String name, Object value);

    /**
     * 注册变量（可变绑定）。
     *
     * @param name  变量名
     * @param value 变量值
     * @return 当前 Quick 实例（链式）
     */
    Quick variable(String name, Object value);

    /**
     * 获取变量值。
     *
     * @param name 变量名
     * @return 变量值，不存在返回 null
     */
    Object variable(String name);

    /**
     * 设置环境属性（写入内部 ObjectContext 的 Environment）。
     *
     * @param key   属性键
     * @param value 属性值
     * @return 当前 Quick 实例（链式）
     */
    Quick env(String key, Object value);

    // ==================== 数据导入 ====================

    /**
     * 从 JSON 字符串导入数据（解析为 Map 并绑定为变量）。
     *
     * @param json JSON 字符串
     * @return 当前 Quick 实例（链式）
     */
    Quick fromJson(String json);

    /**
     * 从 XML 字符串导入数据（解析为 Map 并绑定为变量）。
     *
     * @param xml XML 字符串
     * @return 当前 Quick 实例（链式）
     */
    Quick fromXml(String xml);

    /**
     * 从 JSON 字符串导入并转换为指定类型对象。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T>  泛型类型
     * @return 转换后的对象
     */
    <T> T fromJson(String json, Class<T> type);

    /**
     * 从 XML 字符串导入并转换为指定类型对象。
     *
     * @param xml  XML 字符串
     * @param type 目标类型
     * @param <T>  泛型类型
     * @return 转换后的对象
     */
    <T> T fromXml(String xml, Class<T> type);

    // ==================== 类初始化 ====================

    /**
     * 初始化指定类型的对象。
     *
     * <p>若之前通过 {@link #fromJson(String)} / {@link #fromXml(String)} 导入了数据，
     * 则优先将数据转换为目标类型；否则从上下文创建实例。</p>
     *
     * @param type 目标类型
     * @param <T>  泛型类型
     * @return 初始化后的对象
     */
    <T> T init(Class<T> type);

    /**
     * 根据名称（含导入包解析）初始化对象。
     *
     * @param className 类全限定名或简单名（简单名将按导入包解析）
     * @param <T>       泛型类型
     * @return 初始化后的对象
     */
    <T> T init(String className);

    // ==================== 集合构造器 ====================

    /**
     * 创建 Map 链式构造器。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return Map 构造器
     */
    <K, V> MapBuilder<K, V> map();

    /**
     * 创建 List 链式构造器。
     *
     * @param <E> 元素类型
     * @return List 构造器
     */
    <E> ListBuilder<E> list();

    /**
     * 创建 Table 链式构造器（Guava Table，row/column/value 三维）。
     *
     * @param <R> 行类型（需 {@link Comparable}，用于 TreeBasedTable）
     * @param <C> 列类型（需 {@link Comparable}，用于 TreeBasedTable）
     * @param <V> 值类型
     * @return Table 构造器
     */
    <R extends Comparable<? super R>, C extends Comparable<? super C>, V> TableBuilder<R, C, V> table();

    // ==================== 动态类 ====================

    /**
     * 编译 Java 源码为 Class（Compiler SPI：jdk 默认，asm/groovy 等按依赖自动发现）。
     *
     * @param source Java 源码字符串
     * @return 编译后的 Class
     */
    Class<?> compile(String source);

    /**
     * 动态生成指定父类型（类或接口）的子类并实例化。
     *
     * <p>内部通过 {@link Compiler} SPI 实现：common-starter 默认使用 JdkCompiler，
     * 引入 utils-support-asm-starter 后自动切换到 ASM/Javassist 实现。</p>
     *
     * @param superType 父类型（接口或类）
     * @param source    子类成员源码（方法体），如 {@code "public void run() { ... }"}
     * @param <T>       泛型类型
     * @return 子类实例
     */
    <T> T dynamic(Class<T> superType, String source);

    // ==================== 脚本执行 ====================

    /**
     * 执行脚本。
     *
     * <p>支持两种形式：</p>
     * <ul>
     *   <li><b>完整类源码</b>（包含 {@code class}/{@code interface}/{@code record} 关键字）：
     *       编译后实例化，若实现 {@link QuickScript} 则调用其 {@code run} 方法，否则查找
     *       {@code run()}/{@code execute()} 或静态 {@code main(String[])} 调用。</li>
     *   <li><b>代码片段</b>：包装为 {@link QuickScript} 实现类后编译执行，片段内可访问
     *       {@code quick}（当前实例）与 {@code variables}（绑定变量 Map）。</li>
     * </ul>
     *
     * <p><b>安全提示：</b>{@code execute} 会编译并执行任意 Java 源码，等同于远程代码执行能力，
     * 仅应在受信任的脚本/配置来源下使用（与 GroovyShell 等脚本门面一致）。</p>
     *
     * @param script Java 脚本源码或代码片段
     * @return 执行结果
     */
    Object execute(String script);

    /**
     * 执行脚本并按指定类型返回结果。
     *
     * @param script     脚本源码或代码片段
     * @param returnType 返回类型
     * @param <T>        泛型类型
     * @return 类型化执行结果
     */
    <T> T execute(String script, Class<T> returnType);

    // ==================== Bean 访问 ====================

    /**
     * 按名称注册 Bean 到内部上下文。
     *
     * @param name Bean 名称
     * @param bean Bean 实例
     * @return 当前 Quick 实例（链式）
     */
    Quick register(String name, Object bean);

    /**
     * 按名称获取 Bean。
     *
     * @param name Bean 名称
     * @param <T>  泛型类型
     * @return Bean 实例，不存在返回 null
     */
    <T> T get(String name);

    /**
     * 按类型获取 Bean。
     *
     * @param type Bean 类型
     * @param <T>  泛型类型
     * @return Bean 实例，不存在返回 null
     */
    <T> T get(Class<T> type);

    /**
     * 关闭 Quick，释放内部上下文资源。
     */
    @Override
    void close();

    // ==================== 内部构造器接口 ====================

    /**
     * Map 链式构造器，支持多种 Map 实现。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    interface MapBuilder<K, V> {

        /**
         * 添加键值对。
         *
         * @param key   键
         * @param value 值
         * @return 当前构造器（链式）
         */
        MapBuilder<K, V> put(K key, V value);

        /**
         * 指定 Map 实现类型。
         *
         * @param type 实现类型：{@code hash}（HashMap 默认）、{@code linked}（LinkedHashMap）、
         *             {@code tree}（TreeMap）、{@code concurrent}（ConcurrentHashMap）
         * @return 当前构造器（链式）
         */
        MapBuilder<K, V> type(String type);

        /**
         * 构建 Map。
         *
         * @return Map 实例
         */
        Map<K, V> build();
    }

    /**
     * List 链式构造器，支持多种 List 实现。
     *
     * @param <E> 元素类型
     */
    interface ListBuilder<E> {

        /**
         * 添加元素。
         *
         * @param value 元素
         * @return 当前构造器（链式）
         */
        ListBuilder<E> add(E value);

        /**
         * 指定 List 实现类型。
         *
         * @param type 实现类型：{@code array}（ArrayList 默认）、{@code linked}（LinkedList）、
         *             {@code sorted}（SortedArrayList）、{@code sync}（CopyOnWriteArrayList）
         * @return 当前构造器（链式）
         */
        ListBuilder<E> type(String type);

        /**
         * 构建 List。
         *
         * @return List 实例
         */
        List<E> build();
    }

    /**
     * Table 链式构造器（Guava Table）。
     *
     * @param <R> 行类型（需 {@link Comparable}，用于 TreeBasedTable）
     * @param <C> 列类型（需 {@link Comparable}，用于 TreeBasedTable）
     * @param <V> 值类型
     */
    interface TableBuilder<R extends Comparable<? super R>, C extends Comparable<? super C>, V> {

        /**
         * 添加单元格。
         *
         * @param rowKey    行键
         * @param columnKey 列键
         * @param value     值
         * @return 当前构造器（链式）
         */
        TableBuilder<R, C, V> put(R rowKey, C columnKey, V value);

        /**
         * 指定 Table 实现类型。
         *
         * @param type 实现类型：{@code hash}（HashBasedTable 默认）、{@code tree}（TreeBasedTable）
         * @return 当前构造器（链式）
         */
        TableBuilder<R, C, V> type(String type);

        /**
         * 构建 Table。
         *
         * @return Table 实例
         */
        Table<R, C, V> build();
    }
}