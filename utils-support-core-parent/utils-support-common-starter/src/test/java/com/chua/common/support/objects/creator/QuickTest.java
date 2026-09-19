package com.chua.common.support.objects.creator;

import com.chua.common.support.lang.compile.Compiler;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.ServiceProvider;
import com.google.common.collect.Table;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Quick 门面功能冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.common.support.objects.creator.QuickTest}
 * 任一校验失败抛出 {@link AssertionError} 并输出 FAIL，全部通过输出 PASS。
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class QuickTest {

    /**
     * 失败计数
    */
    private static int failureCount = 0;

    /**
     * 成功计数
    */
    private static int passCount = 0;

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) {
        testCreate();
        testIndependentContext();
        testBindings();
        testFromXml();
        testFromJson();
        testFromXmlToType();
        testFromJsonToType();
        testInit();
        testInitFromData();
        testMapBuilder();
        testListBuilder();
        testTableBuilder();
        testDynamic();
        testDynamicExtendsClass();
        testExecuteExpression();
        testExecuteWithVariables();
        testExecuteFullClass();
        testExecuteTyped();
        testSpiRegistration();
        testImportPackageAndInitByName();
        testCompilerSpiSwitch();

        System.out.println("========================================");
        System.out.println("QuickTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 静态创建
    */
    static void testCreate() {
        Quick quick = Quick.create();
        check(quick != null, "Quick.create() 返回实例");
        check(quick.context() instanceof ObjectContext, "context() 返回 ObjectContext");
        quick.close();
    }

    /**
     * 独立上下文
    */
    static void testIndependentContext() {
        Quick quick1 = Quick.create();
        Quick quick2 = Quick.create();
        quick1.variable("name", "zhang");
        check(quick2.get("name") == null, "不同 Quick 实例的变量应互相隔离");
        quick1.close();
        quick2.close();
    }

    /**
     * 常量变量环境绑定
    */
    static void testBindings() {
        Quick quick = Quick.create()
                .constant("PI", 3.14)
                .variable("name", "zhang")
                .env("server.port", "8080");
        check(eq(3.14, quick.get("PI")), "constant 绑定 PI");
        check(eq("zhang", quick.get("name")), "variable 绑定 name");
        check(eq("zhang", quick.variable("name")), "variable() 读取");
        check(eq("8080", quick.context().getEnvironment().getProperty("server.port")), "env 环境属性");
        quick.close();
    }

    /**
     * XML数据导入
    */
    static void testFromXml() {
        Quick quick = Quick.create();
        quick.fromXml("<user><name>li</name><age>30</age></user>");
        check(eq("li", quick.get("name")), "fromXml 绑定 name");
        check(eq("30", quick.get("age")), "fromXml 绑定 age");
        quick.close();
    }

    /**
     * JSON数据导入
    */
    static void testFromJson() {
        Quick quick = Quick.create();
        quick.fromJson("{\"name\":\"wang\",\"age\":25}");
        check(eq("wang", quick.get("name")), "fromJson 绑定 name");
        check(eq(25, quick.get("age")), "fromJson 绑定 age");
        quick.close();
    }

    /**
     * XML转类型对象
    */
    static void testFromXmlToType() {
        Quick quick = Quick.create();
        User user = quick.fromXml("<user><name>li</name><age>30</age></user>", User.class);
        check(user != null, "fromXml 转对象非空");
        check(eq("li", user.getName()), "fromXml 转对象 name");
        check(eq(30, user.getAge()), "fromXml 字符串 age 转 int 成功");
        quick.close();
    }

    /**
     * JSON转类型对象
    */
    static void testFromJsonToType() {
        Quick quick = Quick.create();
        User user = quick.fromJson("{\"name\":\"wang\",\"age\":25}", User.class);
        check(user != null, "fromJson 转对象非空");
        check(eq("wang", user.getName()), "fromJson 转对象 name");
        check(eq(25, user.getAge()), "fromJson 转对象 age");
        quick.close();
    }

    /**
     * 初始化类
    */
    static void testInit() {
        Quick quick = Quick.create();
        User user = quick.init(User.class);
        check(user != null, "init(Class) 创建实例");
        quick.close();
    }

    /**
     * 初始化从数据
    */
    static void testInitFromData() {
        Quick quick = Quick.create();
        quick.fromXml("<user><name>zhao</name><age>40</age></user>");
        User user = quick.init(User.class);
        check(user != null, "init 从数据转换非空");
        check(eq("zhao", user.getName()), "init 从数据转换 name");
        check(eq(40, user.getAge()), "init 从数据转换 age");
        quick.close();
    }

    /**
     * 映射构造器
    */
    static void testMapBuilder() {
        Quick quick = Quick.create();
        Map<String, Integer> map = quick.<String, Integer>map()
                .put("a", 1)
                .put("b", 2)
                .build();
        check(map.size() == 2 && eq(Integer.valueOf(1), map.get("a")), "map 构造器基础");
        Map<String, Integer> tree = quick.<String, Integer>map()
                .type("tree")
                .put("b", 2)
                .put("a", 1)
                .build();
        check(eq(List.of("a", "b"), List.copyOf(tree.keySet())), "map tree 排序");
        quick.close();
    }

    /**
     * 列表构造器
    */
    static void testListBuilder() {
        Quick quick = Quick.create();
        List<String> list = quick.<String>list()
                .add("x")
                .add("y")
                .build();
        check(list.size() == 2, "list 构造器基础");
        List<String> linked = quick.<String>list()
                .type("linked")
                .add("x")
                .build();
        check(linked instanceof LinkedList, "list linked 实现");
        quick.close();
    }

    /**
     * Table构造器
    */
    static void testTableBuilder() {
        Quick quick = Quick.create();
        Table<String, String, Object> table = quick.<String, String, Object>table()
                .put("r1", "c1", 1)
                .put("r1", "c2", 2)
                .build();
        check(eq(Integer.valueOf(1), table.get("r1", "c1")), "table 单元格 c1");
        check(eq(Integer.valueOf(2), table.get("r1", "c2")), "table 单元格 c2");
        quick.close();
    }

    /**
     * 动态类生成
    */
    static void testDynamic() {
        Quick quick = Quick.create();
        Runnable runnable = quick.dynamic(Runnable.class, "public void run() { System.out.println(\"dynamic ok\"); }");
        check(runnable != null, "dynamic 接口子类实例化");
        runnable.run();
        quick.close();
    }

    /**
     * 动态类-继承具体类
    */
    static void testDynamicExtendsClass() {
        Quick quick = Quick.create();
        User user = quick.dynamic(User.class, "public String getName() { return \"dynamic-user\"; }");
        check(user != null, "dynamic 具体类子类实例化");
        check(eq("dynamic-user", user.getName()), "dynamic 覆写方法生效");
        quick.close();
    }

    /**
     * 脚本执行-表达式
    */
    static void testExecuteExpression() {
        Quick quick = Quick.create();
        Object result = quick.execute("1 + 2");
        check(result instanceof Number && ((Number) result).intValue() == 3, "execute 表达式 1+2=3");
        quick.close();
    }

    /**
     * 脚本执行-访问变量
    */
    static void testExecuteWithVariables() {
        Quick quick = Quick.create()
                .variable("x", 10)
                .variable("y", 20);
 // 变量.获取 返回 对象，片段内需自行强转
        Object result = quick.execute(
                "return ((Number) variables.get(\"x\")).intValue() + ((Number) variables.get(\"y\")).intValue();");
        check(result instanceof Number && ((Number) result).intValue() == 30, "execute 访问变量 x+y=30");
        quick.close();
    }

    /**
     * 脚本执行-完整类
    */
    static void testExecuteFullClass() {
        Quick quick = Quick.create();
        Object result = quick.execute(
                "public class QuickTestScript implements com.chua.common.support.objects.creator.QuickScript {"
                        + "  public Object run(Quick quick, Map<String, Object> variables) { return \"full-class\"; }"
                        + "}");
        check(eq("full-class", result), "execute 完整类脚本");
        quick.close();
    }

    /**
     * 脚本执行-返回类型转换
    */
    static void testExecuteTyped() {
        Quick quick = Quick.create();
        Integer result = quick.execute("3 * 4", Integer.class);
        check(eq(12, result), "execute 类型化返回");
        quick.close();
    }

    /**
     * SPI注册
    */
    static void testSpiRegistration() {
        Quick quick = ServiceProvider.of(Quick.class).getExtension("quick");
        check(quick != null, "SPI 发现 DefaultQuick 实现");
        quick.close();
    }

    /**
     * 导入包 + 按名称初始化
    */
    static void testImportPackageAndInitByName() {
        Quick quick = Quick.create()
                .importPackage("java.util");
        ArrayList<String> list = quick.init("ArrayList");
        check(list != null, "importPackage + init(名称) 解析");
        quick.close();
    }

    /**
     * 编译器 SPI：dynamic/compile 自动切换（类路径 含 asm-启动 时使用 ASM）
    */
    static void testCompilerSpiSwitch() {
        Quick quick = Quick.create();
        Compiler compiler = resolveCompilerReflectively(quick);
        check(compiler != null, "编译器 SPI 解析非空");
        String implName = compiler.getClass().getName();
        System.out.println("[INFO] 当前编译器实现: " + implName);
        if (implName.equals("com.chua.common.support.lang.compile.AsmCompiler")) {
 // 类路径 含 utils-support-asm-starter：验证 SPI 注册与 dynamic/compile 均可用
            Compiler asm = ServiceProvider.of(Compiler.class).getExtension("asm");
            check(asm != null && asm.getClass().getName().equals("com.chua.common.support.lang.compile.AsmCompiler"),
                    "asm-starter 在 classpath 时自动解析 AsmCompiler");
            Runnable runnable = quick.dynamic(Runnable.class, "public void run() { System.out.println(\"asm dynamic ok\"); }");
            check(runnable != null, "dynamic() 在 ASM 编译器下生成子类");
            if (runnable != null) {
                runnable.run();
            }
            Class<?> clazz = quick.compile("public class CompiledProbe { public static int add(int a, int b) { return a + b; } }");
            check(clazz != null, "compile() 在 ASM 编译器下编译源码");
        } else {
 // 无 asm-启动：应回退 common-starter 自带 jdkcompiler
            check(implName.equals("com.chua.common.support.lang.compile.JdkCompiler"),
                    "无 asm-starter 时回退 JdkCompiler");
        }
        quick.close();
    }

    /**
     * 反射调用 {@link DefaultQuick#resolveCompiler()} 获取当前解析到的编译器。
     *
     * @param quick Quick 实例（默认quick 实现）
     * @return 当前编译器实现
     */
    private static Compiler resolveCompilerReflectively(Quick quick) {
        try {
            Method method = DefaultQuick.class.getDeclaredMethod("resolveCompiler");
            method.setAccessible(true);
            return (Compiler) method.invoke(quick);
        } catch (Exception e) {
            throw new IllegalStateException("反射获取编译器失败", e);
        }
    }

    /**
     * 测试用用户类
     *
     * @author CH
     */
    public static class User {
        private String name; // 名称
        private int age; // age

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    /**
     * 校验并计数。
     *
     * @param condition 条件
     * @param message   校验说明
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }

    /**
     * 对象相等比较（处理 空）。
     *
     * @param expected 期望值
     * @param actual   实际值
     * @return 是否相等
     */
    private static boolean eq(Object expected, Object actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }
}