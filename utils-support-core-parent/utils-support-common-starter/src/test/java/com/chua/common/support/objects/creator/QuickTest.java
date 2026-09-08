package com.chua.common.support.objects.creator;

import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.ServiceProvider;
import com.google.common.collect.Table;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quick 门面功能测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class QuickTest {

    @Test
    /** 静态create */
    void testCreate() {
        Quick quick = Quick.create();
        assertNotNull(quick);
        assertNotNull(quick.context());
        assertTrue(quick.context() instanceof ObjectContext);
        quick.close();
    }

    @Test
    /** 独立上下文 */
    void testIndependentContext() {
        Quick quick1 = Quick.create();
        Quick quick2 = Quick.create();
        quick1.variable("name", "zhang");
        assertNull(quick2.get("name"), "不同 Quick 实例的变量应互相隔离");
        quick1.close();
        quick2.close();
    }

    @Test
    /** 常量变量环境绑定 */
    void testBindings() {
        Quick quick = Quick.create()
                .constant("PI", 3.14)
                .variable("name", "zhang")
                .env("server.port", "8080");
        assertEquals(3.14, quick.get("PI"));
        assertEquals("zhang", quick.get("name"));
        assertEquals("zhang", quick.variable("name"));
        assertEquals("8080", quick.context().getEnvironment().getProperty("server.port"));
        quick.close();
    }

    @Test
    /** XML数据导入 */
    void testFromXml() {
        Quick quick = Quick.create();
        quick.fromXml("<user><name>li</name><age>30</age></user>");
        assertEquals("li", quick.get("name"));
        assertEquals("30", quick.get("age"));
        quick.close();
    }

    @Test
    /** JSON数据导入 */
    void testFromJson() {
        Quick quick = Quick.create();
        quick.fromJson("{\"name\":\"wang\",\"age\":25}");
        assertEquals("wang", quick.get("name"));
        assertEquals(25, quick.get("age"));
        quick.close();
    }

    @Test
    /** XML转类型对象 */
    void testFromXmlToType() {
        Quick quick = Quick.create();
        User user = quick.fromXml("<user><name>li</name><age>30</age></user>", User.class);
        assertNotNull(user);
        assertEquals("li", user.getName());
        assertEquals(30, user.getAge());
        quick.close();
    }

    @Test
    /** JSON转类型对象 */
    void testFromJsonToType() {
        Quick quick = Quick.create();
        User user = quick.fromJson("{\"name\":\"wang\",\"age\":25}", User.class);
        assertNotNull(user);
        assertEquals("wang", user.getName());
        assertEquals(25, user.getAge());
        quick.close();
    }

    @Test
    /** init类 */
    void testInit() {
        Quick quick = Quick.create();
        User user = quick.init(User.class);
        assertNotNull(user);
        quick.close();
    }

    @Test
    /** init从数据 */
    void testInitFromData() {
        Quick quick = Quick.create();
        quick.fromXml("<user><name>zhao</name><age>40</age></user>");
        User user = quick.init(User.class);
        assertNotNull(user);
        assertEquals("zhao", user.getName());
        assertEquals(40, user.getAge());
        quick.close();
    }

    @Test
    /** Map构造器 */
    void testMapBuilder() {
        Quick quick = Quick.create();
        Map<String, Integer> map = quick.<String, Integer>map()
                .put("a", 1)
                .put("b", 2)
                .build();
        assertEquals(2, map.size());
        assertEquals(Integer.valueOf(1), map.get("a"));
        // tree 类型
        Map<String, Integer> tree = quick.<String, Integer>map()
                .type("tree")
                .put("b", 2)
                .put("a", 1)
                .build();
        assertEquals(List.of("a", "b"), List.copyOf(tree.keySet()));
        quick.close();
    }

    @Test
    /** List构造器 */
    void testListBuilder() {
        Quick quick = Quick.create();
        List<String> list = quick.<String>list()
                .add("x")
                .add("y")
                .build();
        assertEquals(2, list.size());
        List<String> linked = quick.<String>list()
                .type("linked")
                .add("x")
                .build();
        assertTrue(linked instanceof java.util.LinkedList);
        quick.close();
    }

    @Test
    /** Table构造器 */
    void testTableBuilder() {
        Quick quick = Quick.create();
        Table<String, String, Object> table = quick.<String, String, Object>table()
                .put("r1", "c1", 1)
                .put("r1", "c2", 2)
                .build();
        assertEquals(Integer.valueOf(1), table.get("r1", "c1"));
        assertEquals(Integer.valueOf(2), table.get("r1", "c2"));
        quick.close();
    }

    @Test
    /** 动态类生成 */
    void testDynamic() {
        Quick quick = Quick.create();
        Runnable runnable = quick.dynamic(Runnable.class, "public void run() { System.out.println(\"dynamic ok\"); }");
        assertNotNull(runnable);
        runnable.run();
        quick.close();
    }

    @Test
    /** 动态类-继承具体类 */
    void testDynamicExtendsClass() {
        Quick quick = Quick.create();
        AtomicReference<String> ref = new AtomicReference<>();
        User user = quick.dynamic(User.class, "public String getName() { return \"dynamic-user\"; }");
        assertNotNull(user);
        assertEquals("dynamic-user", user.getName());
        quick.close();
    }

    @Test
    /** 脚本执行-表达式 */
    void testExecuteExpression() {
        Quick quick = Quick.create();
        Object result = quick.execute("1 + 2");
        assertEquals(3, ((Number) result).intValue());
        quick.close();
    }

    @Test
    /** 脚本执行-访问变量 */
    void testExecuteWithVariables() {
        Quick quick = Quick.create()
                .variable("x", 10)
                .variable("y", 20);
        Object result = quick.execute("return variables.get(\"x\") + variables.get(\"y\");");
        assertEquals(30, ((Number) result).intValue());
        quick.close();
    }

    @Test
    /** 脚本执行-完整类 */
    void testExecuteFullClass() {
        Quick quick = Quick.create();
        Object result = quick.execute(
                "public class QuickTestScript implements com.chua.common.support.objects.creator.QuickScript {" +
                "  public Object run(Quick quick, Map<String, Object> variables) { return \"full-class\"; }" +
                "}");
        assertEquals("full-class", result);
        quick.close();
    }

    @Test
    /** 脚本执行-返回类型转换 */
    void testExecuteTyped() {
        Quick quick = Quick.create();
        Integer result = quick.execute("3 * 4", Integer.class);
        assertEquals(12, result);
        quick.close();
    }

    @Test
    /** SPI注册 */
    void testSpiRegistration() {
        Quick quick = ServiceProvider.of(Quick.class).getExtension("quick");
        assertNotNull(quick, "Quick 应通过 SPI 发现 DefaultQuick 实现");
        quick.close();
    }

    @Test
    /** 导入包 + 按名称init */
    void testImportPackageAndInitByName() {
        Quick quick = Quick.create()
                .importPackage("java.util");
        ArrayList<String> list = quick.init("ArrayList");
        assertNotNull(list);
        quick.close();
    }

    /** 测试用用户类 */
    public static class User {
        private String name;
        private int age;

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
}